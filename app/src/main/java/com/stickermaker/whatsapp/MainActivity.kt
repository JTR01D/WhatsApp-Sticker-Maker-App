package com.stickermaker.whatsapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stickermaker.whatsapp.databinding.ActivityMainBinding
import com.stickermaker.whatsapp.databinding.DialogCreatePackBinding
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.StickerStorage
import com.stickermaker.whatsapp.utils.WhatsAppChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: StickerPackAdapter
    private val packsList = mutableListOf<StickerPack>()

    companion object {
        const val ADD_PACK_REQUEST_CODE = 2001
    }

    private val selectZipLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importZipFile(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupListeners()
        handleIncomingZipIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { handleIncomingZipIntent(it) }
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share_all_zip -> {
                    shareAllPacksAsZip()
                    true
                }
                R.id.action_import_zip -> {
                    launchZipPicker()
                    true
                }
                else -> false
            }
        }
    }

    private fun shareAllPacksAsZip() {
        val packs = StickerStorage.getAllPacks(this)
        if (packs.isEmpty()) {
            Toast.makeText(this, "No tienes paquetes creados para compartir", Toast.LENGTH_SHORT).show()
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Compartir Colección")
            .setMessage("Comprimiendo todos los paquetes en un solo archivo ZIP...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val zipFile = StickerStorage.exportAllPacksToZip(this@MainActivity)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (zipFile != null && zipFile.exists()) {
                    try {
                        val authority = "${applicationContext.packageName}.fileprovider"
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, authority, zipFile)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Colección completa de Stickers")
                            putExtra(Intent.EXTRA_TEXT, "¡Aquí tienes mi colección completa de stickers (${packs.size} paquetes) para WhatsApp!")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Compartir todos los paquetes por WhatsApp"))
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error al compartir: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "No se pudo generar el archivo ZIP de la colección", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun launchZipPicker() {
        selectZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "*/*"))
    }

    private fun handleIncomingZipIntent(intent: Intent) {
        val action = intent.action
        val uri: Uri? = if (action == Intent.ACTION_VIEW) {
            intent.data
        } else if (action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
        } else {
            null
        }

        if (uri != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle("📦 Importar Paquete de Stickers")
                .setMessage("¿Deseas importar los stickers de este archivo ZIP a tu colección?\n\nLos stickers que ya tengas se verificarán para no duplicarse.")
                .setPositiveButton("Importar") { _, _ ->
                    importZipFile(uri)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun importZipFile(uri: Uri) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Importando Paquete ZIP")
            .setMessage("Descomprimiendo y verificando stickers duplicados...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = StickerStorage.importPackFromZip(this@MainActivity, uri)
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (result.success) {
                    val msg = buildString {
                        appendLine("📦 Paquete: '${result.packName}'")
                        appendLine("\n📥 ${result.newStickersAdded} nuevos stickers agregados.")
                        if (result.duplicatesSkipped > 0) {
                            appendLine("\n🧹 ${result.duplicatesSkipped} stickers duplicados omitidos porque ya los tenías guardados.")
                        }
                        if (result.createdPackCount > 1) {
                            appendLine("\n📁 Debido al límite de 30 stickers por paquete de WhatsApp, se crearon ${result.createdPackCount} paquetes.")
                        }
                    }
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("✅ Importación Completada")
                        .setMessage(msg)
                        .setPositiveButton("Aceptar") { _, _ ->
                            loadPacks()
                        }
                        .show()
                    loadPacks()
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Error al importar ZIP")
                        .setMessage(result.errorMessage ?: "No se pudo procesar el archivo ZIP")
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadPacks()
    }

    private fun setupRecyclerView() {
        adapter = StickerPackAdapter(
            packs = packsList,
            onPackClick = { pack ->
                openPackDetail(pack.identifier)
            },
            onAddToWhatsAppClick = { pack ->
                WhatsAppChecker.addPackToWhatsApp(this, pack, ADD_PACK_REQUEST_CODE)
            },
            onDeleteClick = { pack ->
                confirmDeletePack(pack)
            }
        )

        binding.recyclerViewPacks.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewPacks.adapter = adapter
    }

    private fun setupListeners() {
        binding.fabCreatePack.setOnClickListener {
            showCreatePackDialog()
        }

        binding.btnEmptyCreate.setOnClickListener {
            showCreatePackDialog()
        }

        binding.btnEmptyImportZip.setOnClickListener {
            launchZipPicker()
        }
    }

    private fun loadPacks() {
        val packs = StickerStorage.getAllPacks(this)
        packsList.clear()
        packsList.addAll(packs)
        adapter.notifyDataSetChanged()

        if (packs.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.recyclerViewPacks.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.recyclerViewPacks.visibility = View.VISIBLE
        }
    }

    private fun showCreatePackDialog() {
        val dialogBinding = DialogCreatePackBinding.inflate(layoutInflater)

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Crear Paquete") { _, _ ->
                val name = dialogBinding.etPackName.text?.toString()?.trim().orEmpty()
                val publisher = dialogBinding.etPublisher.text?.toString()?.trim().orEmpty()

                if (name.isEmpty()) {
                    Toast.makeText(this, "Por favor, escribe un nombre para el paquete", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val finalPublisher = if (publisher.isEmpty()) "Personal" else publisher
                val identifier = UUID.randomUUID().toString()

                val newPack = StickerPack(
                    identifier = identifier,
                    name = name,
                    publisher = finalPublisher,
                    trayImageFile = ""
                )

                StickerStorage.savePack(this, newPack)
                openPackDetail(identifier)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPackDetail(packId: String) {
        val intent = Intent(this, PackDetailActivity::class.java).apply {
            putExtra(PackDetailActivity.EXTRA_PACK_ID, packId)
        }
        startActivity(intent)
    }

    private fun confirmDeletePack(pack: StickerPack) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Eliminar este paquete?")
            .setMessage("Se eliminará '${pack.name}' de la aplicación.\n\nLos stickers que ya hayas añadido a WhatsApp no se verán afectados.")
            .setPositiveButton(R.string.delete) { _, _ ->
                StickerStorage.deletePack(this, pack.identifier)
                loadPacks()
                Toast.makeText(this, "Paquete '${pack.name}' eliminado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ADD_PACK_REQUEST_CODE) {
            val error = data?.getStringExtra("validation_error")
            android.util.Log.d("MainActivity", "onActivityResult de WhatsApp: resultCode=$resultCode (RESULT_OK=${RESULT_OK}), error=$error, data=$data")
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "¡Paquete añadido a WhatsApp con éxito!", Toast.LENGTH_SHORT).show()
            } else {
                if (!error.isNullOrEmpty()) {
                    Toast.makeText(this, "WhatsApp error: $error", Toast.LENGTH_LONG).show()
                } else if (resultCode == RESULT_CANCELED) {
                    android.util.Log.d("MainActivity", "WhatsApp canceló la adición o el diálogo fue cerrado")
                }
            }
            loadPacks()
        }
    }
}
