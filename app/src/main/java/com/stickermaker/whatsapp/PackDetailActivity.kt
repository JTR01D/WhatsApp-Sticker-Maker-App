package com.stickermaker.whatsapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stickermaker.whatsapp.databinding.ActivityPackDetailBinding
import com.stickermaker.whatsapp.model.Sticker
import com.stickermaker.whatsapp.model.StickerPack
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

    // Multiple image selector from any location on the device
    private val selectImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
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
        binding.fabAddStickers.setOnClickListener {
            checkAndPickImages()
        }

        binding.btnDetailAddToWhatsApp.setOnClickListener {
            currentPack?.let { pack ->
                WhatsAppChecker.addPackToWhatsApp(this, pack, ADD_PACK_REQUEST_CODE)
            }
        }
    }

    private fun checkAndPickImages() {
        currentPack?.let { pack ->
            if (pack.stickers.size >= 30) {
                Toast.makeText(this, "Este paquete ya tiene el límite máximo de 30 stickers", Toast.LENGTH_SHORT).show()
                return
            }
        }
        selectImagesLauncher.launch("image/*")
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

    private fun processSelectedImages(uris: List<android.net.Uri>) {
        binding.layoutProcessing.visibility = View.VISIBLE
        binding.fabAddStickers.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            var addedCount = 0
            for (uri in uris) {
                val pack = StickerStorage.getPack(this@PackDetailActivity, packId) ?: break
                if (pack.stickers.size >= 30) break

                val success = StickerStorage.addStickerToPack(this@PackDetailActivity, packId, uri)
                if (success) addedCount++
            }

            withContext(Dispatchers.Main) {
                binding.layoutProcessing.visibility = View.GONE
                binding.fabAddStickers.isEnabled = true
                loadPackData()

                if (addedCount > 0) {
                    Toast.makeText(
                        this@PackDetailActivity,
                        "Se agregaron $addedCount stickers con éxito",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@PackDetailActivity,
                        "No se pudieron procesar las imágenes seleccionadas",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
