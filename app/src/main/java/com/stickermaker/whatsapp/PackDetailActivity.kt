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
        binding.tvDetailPublisher.text = "Por ${pack.publisher}"
        binding.tvDetailCount.text = "${pack.stickers.size} / 30"

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
     * Automatically splits stickers into multiple packs if total count exceeds 30.
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

            val pack = StickerStorage.getPack(this@PackDetailActivity, packId) ?: return@launch
            val availableSlots = (30 - pack.stickers.size).coerceAtLeast(0)
            val totalWebpCount = webpUris.size

            val currentPackUris = if (availableSlots > 0) webpUris.take(availableSlots) else emptyList()
            val leftoverUris = if (availableSlots > 0 && totalWebpCount > availableSlots) {
                webpUris.drop(availableSlots)
            } else if (availableSlots == 0) {
                webpUris
            } else {
                emptyList()
            }

            val extraChunks = partitionIntoValidPacks(leftoverUris)
            var processedCount = 0
            var successfulCount = 0

            // 1. Fill current pack
            if (currentPackUris.isNotEmpty()) {
                for (uri in currentPackUris) {
                    val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, packId, uri)
                    if (ok) successfulCount++
                    processedCount++
                    val progressPercent = (processedCount * 100) / totalWebpCount
                    withContext(Dispatchers.Main) {
                        binding.progressBarImport.progress = progressPercent
                        binding.tvProcessingStatus.text = "Importando $processedCount de $totalWebpCount stickers..."
                    }
                    yield()
                }
            }

            // 2. Create extra packs for overflow stickers
            val baseName = pack.name.replace(Regex(" \\(Parte \\d+\\)$"), "")
            var partIndex = 2
            val existingPacks = StickerStorage.getAllPacks(this@PackDetailActivity)
            while (existingPacks.any { it.name == "$baseName (Parte $partIndex)" }) {
                partIndex++
            }

            for (chunk in extraChunks) {
                val newIdentifier = UUID.randomUUID().toString()
                val newPackName = "$baseName (Parte $partIndex)"
                partIndex++

                val newPack = StickerPack(
                    identifier = newIdentifier,
                    name = newPackName,
                    publisher = pack.publisher,
                    trayImageFile = ""
                )
                StickerStorage.savePack(this@PackDetailActivity, newPack)

                for (uri in chunk) {
                    val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, newIdentifier, uri)
                    if (ok) successfulCount++
                    processedCount++
                    val progressPercent = (processedCount * 100) / totalWebpCount
                    withContext(Dispatchers.Main) {
                        binding.progressBarImport.progress = progressPercent
                        binding.tvProcessingStatus.text = "Importando en '$newPackName': $processedCount de $totalWebpCount..."
                    }
                    yield()
                }
            }

            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                setControlsEnabled(true)
                loadPackData()

                if (extraChunks.isNotEmpty()) {
                    MaterialAlertDialogBuilder(this@PackDetailActivity)
                        .setTitle("¡Importación Completada!")
                        .setMessage("Se importaron $successfulCount stickers .webp exitosamente.\n\nPara respetar el límite de 30 stickers por paquete de WhatsApp, se crearon automáticamente ${extraChunks.size} paquete(s) adicional(es) (Parte 2, Parte 3...). ¡Ningún sticker se perdió!")
                        .setPositiveButton("Entendido", null)
                        .show()
                } else {
                    Toast.makeText(
                        this@PackDetailActivity,
                        "Se importaron $successfulCount stickers .webp exitosamente",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Processes individually selected files, strictly checking for .webp format
     * and automatically splitting into multiple packs if count > 30.
     */
    private fun processSelectedImages(uris: List<Uri>) {
        binding.layoutProcessing.visibility = View.VISIBLE
        binding.progressBarImport.progress = 0
        binding.tvProcessingStatus.text = "Procesando stickers seleccionados..."
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            val pack = StickerStorage.getPack(this@PackDetailActivity, packId) ?: return@launch
            val availableSlots = (30 - pack.stickers.size).coerceAtLeast(0)
            val totalCount = uris.size

            val currentPackUris = if (availableSlots > 0) uris.take(availableSlots) else emptyList()
            val leftoverUris = if (availableSlots > 0 && totalCount > availableSlots) {
                uris.drop(availableSlots)
            } else if (availableSlots == 0) {
                uris
            } else {
                emptyList()
            }

            val extraChunks = partitionIntoValidPacks(leftoverUris)
            var processedCount = 0
            var addedCount = 0
            var excludedCount = 0

            // 1. Fill current pack
            for (uri in currentPackUris) {
                val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, packId, uri)
                if (ok) addedCount++ else excludedCount++
                processedCount++
                val percent = (processedCount * 100) / totalCount
                withContext(Dispatchers.Main) {
                    binding.progressBarImport.progress = percent
                    binding.tvProcessingStatus.text = "Importando: $processedCount de $totalCount stickers..."
                }
                yield()
            }

            // 2. Extra packs for remainder
            val baseName = pack.name.replace(Regex(" \\(Parte \\d+\\)$"), "")
            var partIndex = 2
            val existingPacks = StickerStorage.getAllPacks(this@PackDetailActivity)
            while (existingPacks.any { it.name == "$baseName (Parte $partIndex)" }) {
                partIndex++
            }

            for (chunk in extraChunks) {
                val newIdentifier = UUID.randomUUID().toString()
                val newPackName = "$baseName (Parte $partIndex)"
                partIndex++

                val newPack = StickerPack(
                    identifier = newIdentifier,
                    name = newPackName,
                    publisher = pack.publisher,
                    trayImageFile = ""
                )
                StickerStorage.savePack(this@PackDetailActivity, newPack)

                for (uri in chunk) {
                    val ok = StickerStorage.addStickerToPack(this@PackDetailActivity, newIdentifier, uri)
                    if (ok) addedCount++ else excludedCount++
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

                if (extraChunks.isNotEmpty()) {
                    MaterialAlertDialogBuilder(this@PackDetailActivity)
                        .setTitle("¡Importación Completada!")
                        .setMessage("Se importaron $addedCount stickers .webp ($excludedCount excluidos por no ser .webp).\n\nSe crearon automáticamente ${extraChunks.size} paquete(s) adicional(es) para respetar el límite de 30 de WhatsApp.")
                        .setPositiveButton("Entendido", null)
                        .show()
                } else if (addedCount > 0) {
                    val msg = if (excludedCount > 0) {
                        "Se agregaron $addedCount stickers .webp ($excludedCount archivos no .webp excluidos)"
                    } else {
                        "Se agregaron $addedCount stickers .webp"
                    }
                    Toast.makeText(this@PackDetailActivity, msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@PackDetailActivity,
                        "Solo se admiten archivos .webp. El resto de formatos fue excluido.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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
