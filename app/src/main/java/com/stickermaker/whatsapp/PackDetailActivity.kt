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
        currentPack?.let { pack ->
            if (pack.stickers.size >= 30) {
                Toast.makeText(this, "Este paquete ya tiene el límite máximo de 30 stickers", Toast.LENGTH_SHORT).show()
                return
            }
        }
        selectFolderLauncher.launch(null)
    }

    private fun launchFilePicker() {
        currentPack?.let { pack ->
            if (pack.stickers.size >= 30) {
                Toast.makeText(this, "Este paquete ya tiene el límite máximo de 30 stickers", Toast.LENGTH_SHORT).show()
                return
            }
        }
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
     * Scans selected folder and imports ONLY .webp files, excluding all others.
     */
    private fun importWebpFromFolder(treeUri: Uri) {
        binding.layoutProcessing.visibility = View.VISIBLE
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

            var addedCount = 0
            var nonWebpCount = 0

            for (uri in webpUris) {
                val pack = StickerStorage.getPack(this@PackDetailActivity, packId) ?: break
                if (pack.stickers.size >= 30) break

                val success = StickerStorage.addStickerToPack(this@PackDetailActivity, packId, uri)
                if (success) {
                    addedCount++
                } else {
                    nonWebpCount++
                }
            }

            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                setControlsEnabled(true)
                loadPackData()

                if (addedCount > 0) {
                    val msg = if (nonWebpCount > 0) {
                        "Se importaron $addedCount stickers .webp ($nonWebpCount archivos no válidos excluidos)"
                    } else {
                        "Se importaron $addedCount stickers .webp exitosamente"
                    }
                    Toast.makeText(this@PackDetailActivity, msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@PackDetailActivity,
                        "Ningún archivo cumplió el formato .webp requerido",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Processes individually selected files, strictly checking for .webp format.
     */
    private fun processSelectedImages(uris: List<Uri>) {
        binding.layoutProcessing.visibility = View.VISIBLE
        setControlsEnabled(false)

        lifecycleScope.launch(Dispatchers.IO) {
            var addedCount = 0
            var excludedCount = 0

            for (uri in uris) {
                val pack = StickerStorage.getPack(this@PackDetailActivity, packId) ?: break
                if (pack.stickers.size >= 30) break

                val success = StickerStorage.addStickerToPack(this@PackDetailActivity, packId, uri)
                if (success) {
                    addedCount++
                } else {
                    excludedCount++
                }
            }

            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                setControlsEnabled(true)
                loadPackData()

                if (addedCount > 0) {
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
            loadPackData()
        }
    }
}
