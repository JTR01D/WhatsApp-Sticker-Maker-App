package com.stickermaker.whatsapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stickermaker.whatsapp.databinding.ActivityPackDetailBinding
import com.stickermaker.whatsapp.model.Sticker
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.ImageProcessor
import com.stickermaker.whatsapp.utils.StickerStorage
import com.stickermaker.whatsapp.utils.WhatsAppChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
        setupRecyclerView()
        setupListeners()
        loadPackData()
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
            onDeleteStickerClick = { sticker ->
                confirmDeleteSticker(sticker)
            }
        )

        binding.recyclerViewStickers.layoutManager = GridLayoutManager(this, 3)
        binding.recyclerViewStickers.adapter = adapter
    }

    private fun setupListeners() {
        // FAB shows choice dialog (Folder or Files)
        binding.fabAddStickers.setOnClickListener {
            showAddOptionsDialog()
        }

        // Direct button: Import Folder
        binding.btnImportFolder.setOnClickListener {
            launchFolderPicker()
        }

        // Direct button: Import Files
        binding.btnImportFiles.setOnClickListener {
            launchFilePicker()
        }

        binding.btnDetailAddToWhatsApp.setOnClickListener {
            currentPack?.let { pack ->
                WhatsAppChecker.addPackToWhatsApp(this, pack, ADD_PACK_REQUEST_CODE)
            }
        }
    }

    private fun showAddOptionsDialog() {
        val options = arrayOf(
            "📁 Seleccionar Carpeta completa (.webp)",
            "🖼️ Seleccionar Archivos sueltos (.webp)"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Importar Stickers .webp")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchFolderPicker()
                    1 -> launchFilePicker()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                binding.tvDetailStatus.text = "Faltan $needed stickers para el mínimo de WhatsApp"
                binding.tvDetailStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_warning))
                binding.btnDetailAddToWhatsApp.isEnabled = false
                binding.btnDetailAddToWhatsApp.alpha = 0.6f
            }
            else -> {
                binding.tvDetailStatus.text = "✓ Listo para importar a WhatsApp"
                binding.tvDetailStatus.setTextColor(ContextCompat.getColor(this, R.color.badge_success))
                binding.btnDetailAddToWhatsApp.isEnabled = true
                binding.btnDetailAddToWhatsApp.alpha = 1.0f
            }
        }

        adapter.updateList(pack.stickers)

        if (pack.stickers.isEmpty()) {
            binding.layoutEmptyStickers.visibility = View.VISIBLE
            binding.recyclerViewStickers.visibility = View.GONE
        } else {
            binding.layoutEmptyStickers.visibility = View.GONE
            binding.recyclerViewStickers.visibility = View.VISIBLE
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
            val rootDir = DocumentFile.fromTreeUri(this@PackDetailActivity, treeUri)
            val webpUris = mutableListOf<Uri>()

            rootDir?.listFiles()?.forEach { file ->
                val name = file.name.orEmpty()
                if (file.isFile && (name.endsWith(".webp", ignoreCase = true) || file.type == "image/webp")) {
                    webpUris.add(file.uri)
                }
            }

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
        val validUris = allUris.filter { ImageProcessor.isStrictlyWebP(this@PackDetailActivity, it) }
        val invalidCount = allUris.size - validUris.size

        if (validUris.isEmpty()) {
            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                setControlsEnabled(true)
                Toast.makeText(
                    this@PackDetailActivity,
                    "No se encontraron stickers .webp válidos",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val totalCount = validUris.size
        val staticUris = mutableListOf<Uri>()
        val animatedUris = mutableListOf<Uri>()

        withContext(Dispatchers.Main) {
            binding.tvProcessingStatus.text = "Clasificando $totalCount stickers (estáticos y con movimiento)..."
            binding.progressBarImport.progress = 0
        }

        for ((index, uri) in validUris.withIndex()) {
            if (ImageProcessor.isAnimatedWebP(this@PackDetailActivity, uri)) {
                animatedUris.add(uri)
            } else {
                staticUris.add(uri)
            }
            if (index % 20 == 0) {
                val percent = (index * 100) / totalCount
                withContext(Dispatchers.Main) {
                    binding.progressBarImport.progress = percent
                }
                yield()
            }
        }

        val current = StickerStorage.getPack(this@PackDetailActivity, packId) ?: return
        val baseName = current.name
            .replace(Regex(" \\(Parte \\d+\\)$"), "")
            .replace(Regex(" - Animados.*$"), "")
            .replace(Regex(" - Estáticos.*$"), "")
            .trim()
        val publisher = current.publisher

        var processedCount = 0
        var addedStatic = 0
        var addedAnimated = 0
        var createdExtraPacks = 0

        // Helper to find unique pack name
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

        // Determine destination for static and animated lists
        val fillCurrentWithStatic: Boolean
        val fillCurrentWithAnimated: Boolean

        if (current.stickers.isEmpty()) {
            if (staticUris.isNotEmpty()) {
                fillCurrentWithStatic = true
                fillCurrentWithAnimated = false
            } else {
                fillCurrentWithStatic = false
                fillCurrentWithAnimated = true
            }
        } else {
            if (current.animatedStickerPack) {
                fillCurrentWithStatic = false
                fillCurrentWithAnimated = true
            } else {
                fillCurrentWithStatic = true
                fillCurrentWithAnimated = false
            }
        }

        val availableSlots = (30 - current.stickers.size).coerceAtLeast(0)

        // 1. Handle Static stickers
        val staticForCurrent = if (fillCurrentWithStatic && availableSlots > 0) {
            staticUris.take(availableSlots)
        } else emptyList()

        val staticForExtra = if (fillCurrentWithStatic) {
            staticUris.drop(staticForCurrent.size)
        } else staticUris

        for (uri in staticForCurrent) {
            val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, current.identifier, uri)
            if (ok) addedStatic++
            processedCount++
            val percent = (processedCount * 100) / totalCount
            withContext(Dispatchers.Main) {
                binding.progressBarImport.progress = percent
                binding.tvProcessingStatus.text = "Importando estáticos: $processedCount de $totalCount..."
            }
            yield()
        }

        val staticChunks = partitionIntoValidPacks(staticForExtra)
        for (chunk in staticChunks) {
            val newPackId = UUID.randomUUID().toString()
            val newPackName = getUniquePackName("$baseName (Parte {idx})")
            val newPack = StickerPack(
                identifier = newPackId,
                name = newPackName,
                publisher = publisher,
                trayImageFile = "",
                animatedStickerPack = false
            )
            StickerStorage.savePack(this@PackDetailActivity, newPack)
            createdExtraPacks++

            for (uri in chunk) {
                val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, newPackId, uri)
                if (ok) addedStatic++
                processedCount++
                val percent = (processedCount * 100) / totalCount
                withContext(Dispatchers.Main) {
                    binding.progressBarImport.progress = percent
                    binding.tvProcessingStatus.text = "Importando en '$newPackName': $processedCount de $totalCount..."
                }
                yield()
            }
        }

        // 2. Handle Animated stickers
        val animatedSlots = if (fillCurrentWithAnimated) (30 - (StickerStorage.getPack(this@PackDetailActivity, packId)?.stickers?.size ?: 0)).coerceAtLeast(0) else 0
        val animatedForCurrent = if (fillCurrentWithAnimated && animatedSlots > 0) {
            animatedUris.take(animatedSlots)
        } else emptyList()

        val animatedForExtra = if (fillCurrentWithAnimated) {
            animatedUris.drop(animatedForCurrent.size)
        } else animatedUris

        for (uri in animatedForCurrent) {
            val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, current.identifier, uri)
            if (ok) addedAnimated++
            processedCount++
            val percent = (processedCount * 100) / totalCount
            withContext(Dispatchers.Main) {
                binding.progressBarImport.progress = percent
                binding.tvProcessingStatus.text = "Importando animados: $processedCount de $totalCount..."
            }
            yield()
        }

        val animatedChunks = partitionIntoValidPacks(animatedForExtra)
        for (chunk in animatedChunks) {
            val newPackId = UUID.randomUUID().toString()
            val newPackName = getUniquePackName("$baseName - Animados (Parte {idx})")
            val newPack = StickerPack(
                identifier = newPackId,
                name = newPackName,
                publisher = publisher,
                trayImageFile = "",
                animatedStickerPack = true
            )
            StickerStorage.savePack(this@PackDetailActivity, newPack)
            createdExtraPacks++

            for (uri in chunk) {
                val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, newPackId, uri)
                if (ok) addedAnimated++
                processedCount++
                val percent = (processedCount * 100) / totalCount
                withContext(Dispatchers.Main) {
                    binding.progressBarImport.progress = percent
                    binding.tvProcessingStatus.text = "Importando en '$newPackName': $processedCount de $totalCount..."
                }
                yield()
            }
        }

        withContext(Dispatchers.Main) {
            binding.layoutProcessing.visibility = View.GONE
            setControlsEnabled(true)
            loadPackData()

            val details = buildString {
                append("Resultados de la importación:\n")
                if (addedStatic > 0) append("• $addedStatic stickers estáticos\n")
                if (addedAnimated > 0) append("• $addedAnimated stickers con movimiento (animados)\n")
                if (invalidCount > 0) append("• $invalidCount archivos omitidos (no eran .webp)\n")
                if (createdExtraPacks > 0) {
                    append("\nSe crearon $createdExtraPacks paquete(s) adicional(es) respetando los límites de 3 a 30 stickers y separando los animados de los estáticos como exige WhatsApp.")
                }
            }

            MaterialAlertDialogBuilder(this@PackDetailActivity)
                .setTitle("¡Importación Completada!")
                .setMessage(details)
                .setPositiveButton("Excelente", null)
                .show()
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.fabAddStickers.isEnabled = enabled
        binding.btnImportFolder.isEnabled = enabled
        binding.btnImportFiles.isEnabled = enabled
    }

    private fun confirmDeleteSticker(sticker: Sticker) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.delete_sticker_confirm)
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
