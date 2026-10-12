package com.stickermaker.whatsapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stickermaker.whatsapp.databinding.ActivityPackDetailBinding
import com.stickermaker.whatsapp.databinding.DialogImportChoiceBinding
import com.stickermaker.whatsapp.model.Sticker
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.ImageProcessor
import com.stickermaker.whatsapp.utils.StickerStorage
import com.stickermaker.whatsapp.utils.WhatsAppChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.resume
import java.util.UUID

class PackDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACK_ID = "extra_pack_id"
        private const val ADD_PACK_REQUEST_CODE = 2002
    }

    private lateinit var binding: ActivityPackDetailBinding
    private lateinit var adapter: StickerGridAdapter
    private var packId: String = ""
    private var currentPack: StickerPack? = null

    // Launcher for selecting an entire folder from storage
    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
            if (treeUri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                importWebpFromFolder(treeUri)
            }
        }

    // Launcher for selecting individual or multiple .webp files strictly
    private val selectFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                processSelectedImages(uris)
            }
        }

    // Launcher for selecting a .zip sticker pack
    private val selectZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importZipFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPackDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        packId = intent.getStringExtra(EXTRA_PACK_ID).orEmpty()
        if (packId.isEmpty()) {
            Toast.makeText(this, "Paquete no encontrado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupToolbar()
        setupBackPressHandler()
        setupRecyclerView()
        setupListeners()
        loadPackData()
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    exitStickerSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        adapter = StickerGridAdapter(
            packId = packId,
            stickers = mutableListOf(),
            trayFileName = currentPack?.trayImageFile.orEmpty(),
            onStickerClick = { sticker ->
                showStickerPreviewDialog(sticker)
            },
            onDeleteStickerClick = { sticker ->
                confirmDeleteSticker(sticker)
            },
            onSelectionChanged = { selectedCount ->
                updateStickerSelectionUi(selectedCount)
            }
        )

        binding.recyclerViewStickers.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerViewStickers.adapter = adapter
    }

    private fun setupListeners() {
        // Edit Pack Name
        binding.btnEditPackName.setOnClickListener {
            showEditPackNameDialog()
        }

        // Toggle Sticker Selection Mode
        binding.btnManageStickers.setOnClickListener {
            if (adapter.isSelectionMode) {
                exitStickerSelectionMode()
            } else {
                enterStickerSelectionMode()
            }
        }

        binding.btnSelectAllStickers.setOnClickListener {
            adapter.selectAll()
        }

        binding.btnCloseStickerSelection.setOnClickListener {
            exitStickerSelectionMode()
        }

        binding.btnDeleteSelectedStickers.setOnClickListener {
            confirmDeleteSelectedStickers()
        }

        // FAB allows adding individual files directly up to 30 stickers
        binding.fabAddStickers.setOnClickListener {
            val pack = currentPack ?: return@setOnClickListener
            val available = 30 - pack.stickers.size
            if (available <= 0) {
                Toast.makeText(this, "Este paquete ya tiene el máximo permitido de 30 stickers", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            launchFilePicker()
        }

        // Direct button: WhatsApp Official
        binding.btnImportWhatsApp.setOnClickListener {
            checkAndImportWhatsAppStickersFolder()
        }

        // Direct button: Import Folder
        binding.btnImportFolder.setOnClickListener {
            launchFolderPicker()
        }

        // Direct button: Import Files
        binding.btnImportFiles.setOnClickListener {
            launchFilePicker()
        }

        // Direct button: Import ZIP
        binding.btnImportZip.setOnClickListener {
            launchZipPicker()
        }

        // Share / Export Pack as ZIP
        binding.btnExportZip.setOnClickListener {
            sharePackAsZip()
        }

        binding.btnDetailAddToWhatsApp.setOnClickListener {
            currentPack?.let { pack ->
                WhatsAppChecker.addPackToWhatsApp(this, pack, ADD_PACK_REQUEST_CODE)
            }
        }
    }

    private fun showAddOptionsDialog() {
        val options = arrayOf(
            "💬 WhatsApp Oficial (Detección Automática)\n   Carga tus stickers recibidos y favoritos al instante",
            "📁 Seleccionar Carpeta Completa\n   Elige cualquier carpeta de tu teléfono (.webp)",
            "🖼️ Elegir Archivos Individuales\n   Selecciona uno o más archivos .webp sueltos",
            "📦 Importar Paquete ZIP (.zip)\n   Carga un paquete con verificación anti-duplicados"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("¿Cómo deseas importar tus stickers?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkAndImportWhatsAppStickersFolder()
                    1 -> launchFolderPicker()
                    2 -> launchFilePicker()
                    3 -> launchZipPicker()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchZipPicker() {
        selectZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
    }

    private fun sharePackAsZip() {
        val pack = currentPack ?: return
        if (pack.stickers.isEmpty()) {
            Toast.makeText(this, "El paquete debe tener al menos 1 sticker para exportarlo", Toast.LENGTH_SHORT).show()
            return
        }

        binding.layoutProcessing.visibility = View.VISIBLE
        binding.progressBarImport.isIndeterminate = true
        binding.tvProcessingStatus.text = "Comprimiendo paquete en ZIP..."
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val zipFile = StickerStorage.exportPackToZip(this@PackDetailActivity, packId)
            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                binding.progressBarImport.isIndeterminate = false
                setControlsEnabled(true)

                if (zipFile != null && zipFile.exists()) {
                    try {
                        val authority = "${applicationContext.packageName}.fileprovider"
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@PackDetailActivity, authority, zipFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Paquete de Stickers: ${pack.name}")
                            putExtra(Intent.EXTRA_TEXT, "¡Aquí tienes mi paquete de stickers '${pack.name}' para WhatsApp!")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Compartir paquete por WhatsApp o Mensaje"))
                    } catch (e: Exception) {
                        Toast.makeText(this@PackDetailActivity, "Error al compartir: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@PackDetailActivity, "No se pudo generar el archivo ZIP", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun importZipFile(uri: Uri) {
        binding.layoutProcessing.visibility = View.VISIBLE
        binding.progressBarImport.isIndeterminate = true
        binding.tvProcessingStatus.text = "Extrayendo y verificando duplicados del ZIP..."
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val result = StickerStorage.importPackFromZip(this@PackDetailActivity, uri, targetPackId = packId)
            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                binding.progressBarImport.isIndeterminate = false
                setControlsEnabled(true)

                if (result.success) {
                    val msg = buildString {
                        appendLine("📥 Se agregaron ${result.newStickersAdded} nuevos stickers a tu colección.")
                        if (result.duplicatesSkipped > 0) {
                            appendLine("\n🧹 Se omitieron ${result.duplicatesSkipped} stickers duplicados porque ya los tenías guardados.")
                        }
                        if (result.createdPackCount > 1) {
                            appendLine("\n📁 Debido al límite de 30 stickers por paquete de WhatsApp, se crearon ${result.createdPackCount} paquetes.")
                        }
                    }
                    MaterialAlertDialogBuilder(this@PackDetailActivity)
                        .setTitle("✅ Importación Completada")
                        .setMessage(msg)
                        .setPositiveButton("Aceptar") { _, _ ->
                            loadPackData()
                        }
                        .show()
                    loadPackData()
                } else {
                    MaterialAlertDialogBuilder(this@PackDetailActivity)
                        .setTitle("Error al importar ZIP")
                        .setMessage(result.errorMessage ?: "No se pudo procesar el archivo ZIP")
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
            }
        }
    }

    private fun checkAndImportWhatsAppStickersFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Acceso a Stickers de WhatsApp")
                    .setMessage("Para leer directamente los stickers que recibes y guardas en WhatsApp, Android requiere conceder el permiso de acceso a archivos.\n\nToca en 'Conceder Permiso' y habilita el interruptor para esta app.")
                    .setPositiveButton("Conceder Permiso") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Ahora no", null)
                    .show()
                return
            }
        }

        val candidates = listOf(
            File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"),
            File("/sdcard/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"),
            File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Backup Excluded Stickers"),
            File("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/.Shared"),
            File("/storage/emulated/0/WhatsApp/Media/WhatsApp Stickers"),
            File("/sdcard/WhatsApp/Media/WhatsApp Stickers"),
            File("/storage/emulated/0/Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers")
        )

        val targetDirs = candidates.filter { it.exists() && it.isDirectory }
        if (targetDirs.isEmpty()) {
            Toast.makeText(this, "No se encontró la carpeta oficial de WhatsApp en el almacenamiento", Toast.LENGTH_LONG).show()
            launchFolderPicker()
            return
        }

        importWhatsAppDirectories(targetDirs)
    }

    private fun importWhatsAppDirectories(directories: List<File>) {
        binding.layoutProcessing.visibility = View.VISIBLE
        binding.progressBarImport.progress = 0
        binding.tvProcessingStatus.text = "Escaneando stickers de WhatsApp..."
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val allFiles = mutableListOf<File>()
            for (dir in directories) {
                dir.listFiles()?.filter {
                    it.isFile && it.name.endsWith(".webp", ignoreCase = true)
                }?.let { allFiles.addAll(it) }
            }

            if (allFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    binding.layoutProcessing.visibility = View.GONE
                    setControlsEnabled(true)
                    Toast.makeText(this@PackDetailActivity, "No se encontraron stickers .webp en WhatsApp", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // Ordenar por fecha de modificación descendente (los más recientes y favoritos primero)
            val sortedFiles = allFiles.distinctBy { it.name }.sortedByDescending { it.lastModified() }
            val uris = sortedFiles.map { Uri.fromFile(it) }
            importUrisBatch(uris)
        }
    }

    private fun launchFolderPicker() {
        selectFolderLauncher.launch(null)
    }

    private fun launchFilePicker() {
        selectFilesLauncher.launch(arrayOf("image/webp"))
    }

    private fun loadPackData() {
        val pack = StickerStorage.getPack(this, packId)
        if (pack == null) {
            finish()
            return
        }
        currentPack = pack

        binding.tvDetailPackName.text = pack.name
        val typeBadge = if (pack.animatedStickerPack) " • 🎬 Animado" else if (pack.stickers.isNotEmpty()) " • 🖼️ Estático" else ""
        binding.tvDetailCount.text = "${pack.stickers.size} / 30$typeBadge"

        // Tray icon
        if (pack.trayImageFile.isNotEmpty()) {
            val trayFile = StickerStorage.getStickerFile(this, pack.identifier, pack.trayImageFile)
            if (trayFile.exists()) {
                Glide.with(this).load(trayFile).into(binding.imgDetailTray)
            }
        }

        // WhatsApp status & minimum 3 stickers validation
        val stickerCount = pack.stickers.size
        when {
            stickerCount < 3 -> {
                val needed = 3 - stickerCount
                binding.tvDetailStatus.text = "⚠️ Faltan $needed stickers para WhatsApp"
                binding.tvDetailStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning))
                binding.layoutStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_container))
                binding.btnDetailAddToWhatsApp.isEnabled = false
                binding.btnDetailAddToWhatsApp.alpha = 0.5f
            }
            else -> {
                binding.tvDetailStatus.text = "✓ Listo para añadir a WhatsApp"
                binding.tvDetailStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_success))
                binding.layoutStatusBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badge_success_container))
                binding.btnDetailAddToWhatsApp.isEnabled = true
                binding.btnDetailAddToWhatsApp.alpha = 1.0f
            }
        }

        adapter.updateList(pack.stickers, pack.trayImageFile)

        if (pack.stickers.isEmpty()) {
            binding.cardImportOptions.visibility = View.VISIBLE
            binding.layoutEmptyStickers.visibility = View.VISIBLE
            binding.recyclerViewStickers.visibility = View.GONE
            binding.btnManageStickers.visibility = View.GONE
            binding.fabAddStickers.visibility = View.GONE
        } else {
            binding.cardImportOptions.visibility = View.GONE
            binding.layoutEmptyStickers.visibility = View.GONE
            binding.recyclerViewStickers.visibility = View.VISIBLE
            binding.btnManageStickers.visibility = View.VISIBLE
            binding.fabAddStickers.visibility = if (pack.stickers.size >= 30) View.GONE else View.VISIBLE
        }
    }

    /**
     * Partitions any number of stickers into chunks of 3..30 items to comply
     * with WhatsApp's strict pack size constraints (min 3, max 30).
     */
    private fun <T> partitionIntoValidPacks(items: List<T>): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        if (items.size <= 30) return listOf(items)

        val result = mutableListOf<List<T>>()
        var startIndex = 0
        val total = items.size

        while (startIndex < total) {
            val remaining = total - startIndex
            if (remaining <= 30) {
                result.add(items.subList(startIndex, total))
                break
            } else if (remaining in 31..32) {
                val half = remaining / 2
                result.add(items.subList(startIndex, startIndex + half))
                result.add(items.subList(startIndex + half, total))
                break
            } else {
                result.add(items.subList(startIndex, startIndex + 30))
                startIndex += 30
            }
        }
        return result
    }

    /**
     * Scans selected folder and imports ALL .webp files smoothly without freezing the device.
     * Separates animated and static stickers into WhatsApp-compliant packs (3..30 stickers each).
     */
    private fun importWebpFromFolder(treeUri: Uri) {
        binding.layoutProcessing.visibility = View.VISIBLE
        binding.progressBarImport.progress = 0
        binding.tvProcessingStatus.text = "Escaneando carpeta en busca de stickers .webp..."
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rootDir = DocumentFile.fromTreeUri(this@PackDetailActivity, treeUri)
                val webpFiles = rootDir?.listFiles()?.filter { file ->
                    val name = file.name.orEmpty()
                    file.isFile && (name.endsWith(".webp", ignoreCase = true) || file.type == "image/webp")
                }?.sortedByDescending { it.lastModified() } ?: emptyList()

                val webpUris = webpFiles.map { it.uri }

                if (webpUris.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        binding.layoutProcessing.visibility = View.GONE
                        setControlsEnabled(true)
                        Toast.makeText(
                            this@PackDetailActivity,
                            "No se encontraron archivos .webp en la carpeta seleccionada",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                importUrisBatch(webpUris)
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.layoutProcessing.visibility = View.GONE
                    setControlsEnabled(true)
                    MaterialAlertDialogBuilder(this@PackDetailActivity)
                        .setTitle("Restricción del Sistema")
                        .setMessage("Android no permite leer carpetas dentro de 'Android/media' usando este selector general.\n\nUsa la opción directa '💬 WhatsApp Stickers Oficial' para cargarlos al instante sin restricciones.")
                        .setPositiveButton("Usar WhatsApp Oficial") { _, _ ->
                            checkAndImportWhatsAppStickersFolder()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    /**
     * Processes individually selected files, separating static and animated stickers.
     */
    private fun processSelectedImages(uris: List<Uri>) {
        binding.layoutProcessing.visibility = View.VISIBLE
        binding.progressBarImport.progress = 0
        binding.tvProcessingStatus.text = "Procesando stickers seleccionados..."
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            importUrisBatch(uris)
        }
    }

    private suspend fun importUrisBatch(allUris: List<Uri>) {
        if (allUris.isEmpty()) {
            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                setControlsEnabled(true)
                Toast.makeText(this@PackDetailActivity, "No se seleccionaron archivos", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val totalCount = allUris.size
        val staticUris = mutableListOf<Uri>()
        val animatedUris = mutableListOf<Uri>()
        val (existingPayloads, existingVisuals) = StickerStorage.getAllExistingStickerSignatures(this@PackDetailActivity)
        val seenPayloads = mutableSetOf<String>()
        val seenVisuals = mutableSetOf<String>()
        var duplicatesCount = 0
        var invalidCount = 0

        withContext(Dispatchers.Main) {
            binding.tvProcessingStatus.text = "Analizando $totalCount stickers y detectando duplicados..."
            binding.progressBarImport.progress = 0
        }

        for ((index, uri) in allUris.withIndex()) {
            val inspection = ImageProcessor.inspectWebP(this@PackDetailActivity, uri)
            if (!inspection.isValidWebP) {
                invalidCount++
            } else {
                val isDup = (inspection.payloadHash.isNotEmpty() && (existingPayloads.contains(inspection.payloadHash) || seenPayloads.contains(inspection.payloadHash))) ||
                            (inspection.visualDHash.isNotEmpty() && (existingVisuals.contains(inspection.visualDHash) || seenVisuals.contains(inspection.visualDHash)))

                if (isDup) {
                    duplicatesCount++
                } else {
                    if (inspection.payloadHash.isNotEmpty()) seenPayloads.add(inspection.payloadHash)
                    if (inspection.visualDHash.isNotEmpty()) seenVisuals.add(inspection.visualDHash)
                    if (inspection.isAnimated) {
                        animatedUris.add(uri)
                    } else {
                        staticUris.add(uri)
                    }
                }
            }

            if (index % 25 == 0 || index == totalCount - 1) {
                val percent = (index * 100) / totalCount
                withContext(Dispatchers.Main) {
                    binding.progressBarImport.progress = percent
                    binding.tvProcessingStatus.text = "Analizando stickers ($index de $totalCount)..."
                }
                yield()
            }
        }

        if (staticUris.isEmpty() && animatedUris.isEmpty()) {
            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                setControlsEnabled(true)
                val msg = if (duplicatesCount > 0) "Todos los stickers analizados eran copias duplicadas" else "No se encontraron stickers .webp válidos"
                Toast.makeText(this@PackDetailActivity, msg, Toast.LENGTH_LONG).show()
            }
            return
        }

        val uniqueTotal = animatedUris.size + staticUris.size

        // Ask the user what they want to import if both types are detected
        val choice: Int = if (staticUris.isNotEmpty() && animatedUris.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                suspendCancellableCoroutine { continuation ->
                    val dialogBinding = DialogImportChoiceBinding.inflate(layoutInflater)

                    dialogBinding.tvScanSubtitle.text = "$uniqueTotal stickers únicos listos para procesar"
                    dialogBinding.tvAnimatedCountBadge.text = "• 🎬 ${animatedUris.size} con movimiento (animados)"
                    dialogBinding.tvStaticCountBadge.text = "• 🖼️ ${staticUris.size} imágenes fijas (estáticas)"

                    if (duplicatesCount > 0) {
                        dialogBinding.tvDuplicatesBadge.visibility = View.VISIBLE
                        dialogBinding.tvDuplicatesBadge.text = "• 🧹 $duplicatesCount stickers duplicados omitidos automáticamente"
                    } else {
                        dialogBinding.tvDuplicatesBadge.visibility = View.GONE
                    }

                    dialogBinding.tvTitleAnimated.text = "Solo con Movimiento (${animatedUris.size})"
                    dialogBinding.tvTitleStatic.text = "Solo Estáticos (${staticUris.size})"
                    dialogBinding.tvTitleBoth.text = "Importar Todos ($uniqueTotal)"

                    val dialog = MaterialAlertDialogBuilder(this@PackDetailActivity)
                        .setView(dialogBinding.root)
                        .setCancelable(true)
                        .setOnCancelListener {
                            if (continuation.isActive) continuation.resume(-1)
                        }
                        .create()

                    dialogBinding.cardChoiceAnimated.setOnClickListener {
                        dialog.dismiss()
                        if (continuation.isActive) continuation.resume(0)
                    }

                    dialogBinding.cardChoiceStatic.setOnClickListener {
                        dialog.dismiss()
                        if (continuation.isActive) continuation.resume(1)
                    }

                    dialogBinding.cardChoiceBoth.setOnClickListener {
                        dialog.dismiss()
                        if (continuation.isActive) continuation.resume(2)
                    }

                    dialogBinding.btnCancelChoice.setOnClickListener {
                        dialog.dismiss()
                        if (continuation.isActive) continuation.resume(-1)
                    }

                    dialog.show()
                }
            }
        } else if (animatedUris.isNotEmpty()) {
            0 // Only animated found
        } else {
            1 // Only static found
        }

        if (choice == -1) {
            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                setControlsEnabled(true)
            }
            return
        }

        withContext(Dispatchers.Main) {
            binding.layoutProcessing.visibility = View.VISIBLE
            binding.progressBarImport.progress = 0
            binding.tvProcessingStatus.text = "Preparando importación..."
        }

        val targetStaticUris = if (choice == 1 || choice == 2) staticUris else emptyList()
        val targetAnimatedUris = if (choice == 0 || choice == 2) animatedUris else emptyList()

        val current = StickerStorage.getPack(this@PackDetailActivity, packId) ?: return
        val isPackInitiallyEmpty = current.stickers.isEmpty()

        val baseName = current.name
            .replace(Regex(" \\(Parte \\d+\\)$"), "")
            .replace(Regex(" - Animados.*$"), "")
            .replace(Regex(" - Estáticos.*$"), "")
            .trim()
        val publisher = current.publisher

        fun getUniquePackName(pattern: String): String {
            val existing = StickerStorage.getAllPacks(this@PackDetailActivity)
            var idx = 1
            var candidate = pattern.replace("{idx}", idx.toString())
            while (existing.any { it.name.equals(candidate, ignoreCase = true) }) {
                idx++
                candidate = pattern.replace("{idx}", idx.toString())
            }
            return candidate
        }

        var addedCount = 0
        var createdExtraPacks = 0
        val newlyCreatedPackIds = mutableListOf<String>()

        if (isPackInitiallyEmpty) {
            // Pack nuevo/vacio: importar TODOS los stickers dividiendolos en paquetes validos (3..30 cada uno)
            suspend fun processListIntoPacks(uris: List<Uri>, isAnim: Boolean, namePattern: String, useCurrentFirst: Boolean) {
                val chunks = partitionIntoValidPacks(uris)
                for ((idx, chunk) in chunks.withIndex()) {
                    val targetPackId: String
                    val packName: String

                    if (useCurrentFirst && idx == 0) {
                        targetPackId = current.identifier
                        packName = current.name
                        current.animatedStickerPack = isAnim
                        StickerStorage.savePack(this@PackDetailActivity, current)
                    } else {
                        targetPackId = UUID.randomUUID().toString()
                        packName = getUniquePackName(namePattern)
                        newlyCreatedPackIds.add(targetPackId)
                        val newPack = StickerPack(
                            identifier = targetPackId,
                            name = packName,
                            publisher = publisher,
                            trayImageFile = "",
                            animatedStickerPack = isAnim
                        )
                        StickerStorage.savePack(this@PackDetailActivity, newPack)
                        createdExtraPacks++
                    }

                    for (uri in chunk) {
                        val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, targetPackId, uri)
                        if (ok) addedCount++
                        withContext(Dispatchers.Main) {
                            binding.tvProcessingStatus.text = "Guardando en '$packName' ($addedCount stickers)..."
                        }
                    }
                }
            }

            if (choice == 0) {
                // Solo Animados
                processListIntoPacks(targetAnimatedUris, true, "$baseName (Parte {idx})", useCurrentFirst = true)
            } else if (choice == 1) {
                // Solo Estaticos
                processListIntoPacks(targetStaticUris, false, "$baseName (Parte {idx})", useCurrentFirst = true)
            } else {
                // Ambos: Animados al paquete actual (y partes), Estaticos en partes separadas
                if (targetAnimatedUris.isNotEmpty()) {
                    processListIntoPacks(targetAnimatedUris, true, "$baseName (Parte {idx})", useCurrentFirst = true)
                    if (targetStaticUris.isNotEmpty()) {
                        processListIntoPacks(targetStaticUris, false, "$baseName - Estáticos (Parte {idx})", useCurrentFirst = false)
                    }
                } else {
                    processListIntoPacks(targetStaticUris, false, "$baseName (Parte {idx})", useCurrentFirst = true)
                }
            }
        } else {
            // Paquete YA GENERADO: solo agregar stickers puntuales a ESTE paquete hasta 30. NUNCA crear paquetes extra!
            val availableSlots = (30 - current.stickers.size).coerceAtLeast(0)
            if (availableSlots <= 0) {
                withContext(Dispatchers.Main) {
                    binding.layoutProcessing.visibility = View.GONE
                    setControlsEnabled(true)
                    Toast.makeText(this@PackDetailActivity, "Este paquete ya tiene el límite máximo de 30 stickers", Toast.LENGTH_LONG).show()
                }
                return
            }

            val chosenUris = if (current.animatedStickerPack) targetAnimatedUris else targetStaticUris
            val toProcess = chosenUris.take(availableSlots)

            for ((idx, uri) in toProcess.withIndex()) {
                val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, current.identifier, uri)
                if (ok) addedCount++
                val percent = ((idx + 1) * 100) / toProcess.size
                withContext(Dispatchers.Main) {
                    binding.progressBarImport.progress = percent
                    binding.tvProcessingStatus.text = "Añadiendo stickers: ${idx + 1} de ${toProcess.size}..."
                }
                yield()
            }
        }

        withContext(Dispatchers.Main) {
            binding.layoutProcessing.visibility = View.GONE
            setControlsEnabled(true)
            loadPackData()

            val details = buildString {
                appendLine("¡Importación completada con éxito!\n")
                if (createdExtraPacks > 0) {
                    appendLine("• 📦 Se crearon ${createdExtraPacks + 1} paquetes en total para cumplir con el límite oficial de WhatsApp (máximo 30 por paquete).")
                }
                appendLine("• ✅ $addedCount stickers añadidos.")
                if (duplicatesCount > 0) {
                    appendLine("• 🧹 $duplicatesCount stickers duplicados omitidos automáticamente.")
                }
                if (invalidCount > 0) {
                    appendLine("• ⚠️ $invalidCount archivos omitidos (no eran .webp válidos).")
                }
            }

            MaterialAlertDialogBuilder(this@PackDetailActivity)
                .setTitle("¡Importación Exitosa!")
                .setMessage(details)
                .setPositiveButton("Aceptar", null)
                .show()
        }
    }

    private fun shareSpecificPacksAsZip(packIds: List<String>) {
        binding.layoutProcessing.visibility = View.VISIBLE
        binding.progressBarImport.isIndeterminate = true
        binding.tvProcessingStatus.text = "Comprimiendo todos los paquetes en un solo ZIP..."
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val zipFile = StickerStorage.exportAllPacksToZip(this@PackDetailActivity, packIds)
            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                binding.progressBarImport.isIndeterminate = false
                setControlsEnabled(true)

                if (zipFile != null && zipFile.exists()) {
                    try {
                        val authority = "${applicationContext.packageName}.fileprovider"
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@PackDetailActivity, authority, zipFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Colección de Stickers (${packIds.size} paquetes)")
                            putExtra(Intent.EXTRA_TEXT, "¡Aquí tienes mi colección completa de stickers (${packIds.size} paquetes) para WhatsApp!")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Compartir todos los paquetes por WhatsApp"))
                    } catch (e: Exception) {
                        Toast.makeText(this@PackDetailActivity, "Error al compartir: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@PackDetailActivity, "No se pudo generar el archivo ZIP de los paquetes", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.fabAddStickers.isEnabled = enabled
        binding.btnImportWhatsApp.isEnabled = enabled
        binding.btnImportFolder.isEnabled = enabled
        binding.btnImportFiles.isEnabled = enabled
    }

    private fun enterStickerSelectionMode() {
        adapter.isSelectionMode = true
        binding.layoutStickerSelectionActions.visibility = View.VISIBLE
        binding.btnManageStickers.text = "Listo"
        updateStickerSelectionUi(adapter.selectedStickerNames.size)
    }

    private fun exitStickerSelectionMode() {
        adapter.isSelectionMode = false
        binding.layoutStickerSelectionActions.visibility = View.GONE
        binding.btnManageStickers.text = "Seleccionar"
    }

    private fun updateStickerSelectionUi(count: Int) {
        if (adapter.isSelectionMode) {
            binding.layoutStickerSelectionActions.visibility = View.VISIBLE
            binding.btnManageStickers.text = "Listo"
            binding.tvStickerSelectionCount.text = "$count seleccionados"
            binding.btnDeleteSelectedStickers.isEnabled = count > 0
            binding.btnDeleteSelectedStickers.alpha = if (count > 0) 1.0f else 0.5f
        } else {
            binding.layoutStickerSelectionActions.visibility = View.GONE
            binding.btnManageStickers.text = "Seleccionar"
        }
    }

    private fun confirmDeleteSelectedStickers() {
        val selected = adapter.selectedStickerNames.toList()
        if (selected.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("¿Eliminar ${selected.size} stickers?")
            .setMessage("Se eliminarán los stickers seleccionados de este paquete.")
            .setPositiveButton(R.string.delete) { _, _ ->
                val pack = currentPack ?: return@setPositiveButton
                for (name in selected) {
                    StickerStorage.removeStickerFromPack(this, packId, name)
                }
                exitStickerSelectionMode()
                loadPackData()
                Toast.makeText(this, "${selected.size} stickers eliminados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showEditPackNameDialog() {
        val pack = currentPack ?: return
        val input = android.widget.EditText(this).apply {
            setText(pack.name)
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
            setTextColor(getColor(R.color.text_primary))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Editar nombre del paquete")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != pack.name) {
                    pack.name = newName
                    StickerStorage.savePack(this, pack)
                    loadPackData()
                    Toast.makeText(this, "Nombre actualizado a '$newName'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showStickerPreviewDialog(sticker: Sticker) {
        val pack = currentPack ?: return
        val stickerFile = StickerStorage.getStickerFile(this, packId, sticker.imageFileName)
        if (!stickerFile.exists()) return

        val dialogBinding = com.stickermaker.whatsapp.databinding.DialogStickerPreviewBinding.inflate(layoutInflater)
        Glide.with(this).load(stickerFile).into(dialogBinding.imgPreviewSticker)

        val isAnimated = ImageProcessor.isAnimatedWebP(this, Uri.fromFile(stickerFile))
        val sizeKb = stickerFile.length() / 1024
        dialogBinding.tvStickerInfo.text = if (isAnimated) "🎬 Sticker animado WebP ($sizeKb KB)" else "🖼️ Sticker estático WebP ($sizeKb KB)"

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        // Set as tray icon
        dialogBinding.btnSetAsTrayIcon.setOnClickListener {
            val trayFile = StickerStorage.getStickerFile(this, packId, "tray_${packId}.webp")
            val success = ImageProcessor.generateTrayIcon(this, Uri.fromFile(stickerFile), trayFile)
            if (success) {
                pack.trayImageFile = "tray_${packId}.webp"
                StickerStorage.savePack(this, pack)
                loadPackData()
                Toast.makeText(this, "🌟 ¡Sticker establecido como ícono del paquete!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No se pudo generar el ícono", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        // Share single sticker
        dialogBinding.btnShareSingleSticker.setOnClickListener {
            try {
                val authority = "${applicationContext.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(this, authority, stickerFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/webp"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir sticker por WhatsApp"))
            } catch (e: Exception) {
                Toast.makeText(this, "Error al compartir: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        // Delete single sticker
        dialogBinding.btnDeleteSingleSticker.setOnClickListener {
            dialog.dismiss()
            confirmDeleteSticker(sticker)
        }

        dialog.show()
    }

    private fun confirmDeleteSticker(sticker: Sticker) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Eliminar este sticker?")
            .setMessage("¿Deseas quitar este sticker del paquete actual?")
            .setPositiveButton(R.string.delete) { _, _ ->
                StickerStorage.removeStickerFromPack(this, packId, sticker.imageFileName)
                loadPackData()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ADD_PACK_REQUEST_CODE) {
            val error = data?.getStringExtra("validation_error")
            android.util.Log.d("PackDetailActivity", "onActivityResult de WhatsApp: resultCode=$resultCode (RESULT_OK=${RESULT_OK}), error=$error, data=$data")
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "¡Paquete añadido a WhatsApp con éxito!", Toast.LENGTH_SHORT).show()
            } else {
                if (!error.isNullOrEmpty()) {
                    Toast.makeText(this, "WhatsApp error: $error", Toast.LENGTH_LONG).show()
                } else if (resultCode == RESULT_CANCELED) {
                    android.util.Log.d("PackDetailActivity", "WhatsApp canceló la adición o el diálogo fue cerrado")
                }
            }
            loadPackData()
        }
    }
}
