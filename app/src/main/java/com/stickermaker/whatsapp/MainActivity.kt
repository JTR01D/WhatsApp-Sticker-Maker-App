package com.stickermaker.whatsapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stickermaker.whatsapp.databinding.ActivityMainBinding
import com.stickermaker.whatsapp.databinding.DialogCreatePackBinding
import com.stickermaker.whatsapp.databinding.DialogSelectPacksWhatsappBinding
import com.stickermaker.whatsapp.databinding.ItemPackSelectBinding
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.StickerStorage
import com.stickermaker.whatsapp.utils.WhatsAppChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Environment
import android.provider.Settings
import com.stickermaker.whatsapp.model.Sticker
import com.stickermaker.whatsapp.utils.ImageProcessor
import java.io.File
import java.util.ArrayDeque
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: StickerPackAdapter
    private val packsList = mutableListOf<StickerPack>()

    // Sequential WhatsApp Addition Queue
    private val sequentialAddQueue = ArrayDeque<StickerPack>()
    private var totalSequentialCount = 0
    private var currentSequentialIndex = 0

    companion object {
        const val ADD_PACK_REQUEST_CODE = 2001
    }

    private val selectZipLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
        setupBackPressHandler()
        handleIncomingZipIntent(intent)
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    exitSelectionMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
                R.id.action_add_multiple_whatsapp -> {
                    showSelectPacksToWhatsAppDialog()
                    true
                }
                R.id.action_share_all_zip -> {
                    shareAllPacksAsZip()
                    true
                }
                R.id.action_import_zip -> {
                    launchZipPicker()
                    true
                }
                R.id.action_delete_all_packs -> {
                    confirmDeleteAllPacks()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = StickerPackAdapter(
            packs = packsList,
            onPackClick = { pack ->
                openPackDetail(pack.identifier)
            },
            onAddToWhatsAppClick = { pack ->
                startSequentialAddToWhatsApp(listOf(pack))
            },
            onDeleteClick = { pack ->
                confirmDeletePack(pack)
            },
            onSelectionChanged = { selectedCount ->
                updateSelectionUi(selectedCount)
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

        // Selection Bar Listeners
        binding.btnSelectAllPacks.setOnClickListener {
            if (adapter.selectedPackIds.size == packsList.size) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }

        binding.btnClosePacksSelection.setOnClickListener {
            exitSelectionMode()
        }

        binding.btnAddSelectedToWhatsApp.setOnClickListener {
            val selected = packsList.filter { adapter.selectedPackIds.contains(it.identifier) }
            val validToAdd = selected.filter { it.stickers.size in 3..30 }

            if (validToAdd.isEmpty()) {
                Toast.makeText(this, "Selecciona paquetes con al menos 3 stickers", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            exitSelectionMode()
            startSequentialAddToWhatsApp(validToAdd)
        }

        binding.btnDeleteSelectedPacks.setOnClickListener {
            confirmDeleteSelectedPacks()
        }
    }

    private fun updateSelectionUi(selectedCount: Int) {
        if (adapter.isSelectionMode) {
            binding.cardPacksSelectionBar.visibility = View.VISIBLE
            binding.fabCreatePack.visibility = View.GONE
            binding.tvSelectedPacksCount.text = "$selectedCount seleccionados"
            binding.btnAddSelectedToWhatsApp.isEnabled = selectedCount > 0
            binding.btnAddSelectedToWhatsApp.alpha = if (selectedCount > 0) 1.0f else 0.5f
            binding.btnDeleteSelectedPacks.isEnabled = selectedCount > 0
            binding.btnDeleteSelectedPacks.alpha = if (selectedCount > 0) 1.0f else 0.5f

            if (selectedCount == packsList.size && packsList.isNotEmpty()) {
                binding.btnSelectAllPacks.text = "Ninguno"
            } else {
                binding.btnSelectAllPacks.text = "Todos"
            }
        } else {
            binding.cardPacksSelectionBar.visibility = View.GONE
            binding.fabCreatePack.visibility = if (packsList.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDeleteSelectedPacks() {
        val selectedIds = adapter.selectedPackIds.toList()
        if (selectedIds.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle("¿Eliminar ${selectedIds.size} paquetes?")
            .setMessage("Se eliminarán los ${selectedIds.size} paquetes seleccionados de la aplicación.\n\nLos stickers que ya hayas añadido a WhatsApp no se verán afectados.")
            .setPositiveButton(R.string.delete) { _, _ ->
                for (id in selectedIds) {
                    StickerStorage.deletePack(this, id)
                }
                exitSelectionMode()
                loadPacks()
                Toast.makeText(this, "${selectedIds.size} paquetes eliminados", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAllPacks() {
        val count = packsList.size
        if (count == 0) {
            Toast.makeText(this, "No hay paquetes creados para eliminar", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("¿Vaciar todos los paquetes?")
            .setMessage("Se eliminarán absolutamente todos los $count paquetes de la aplicación.\n\nEsta acción es irreversible.")
            .setPositiveButton(R.string.delete) { _, _ ->
                for (pack in packsList) {
                    StickerStorage.deletePack(this, pack.identifier)
                }
                exitSelectionMode()
                loadPacks()
                Toast.makeText(this, "Se han eliminado todos los paquetes", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exitSelectionMode() {
        adapter.isSelectionMode = false
        binding.cardPacksSelectionBar.visibility = View.GONE
        binding.fabCreatePack.visibility = if (packsList.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Shows a modal dialog allowing the user to select multiple packs to add sequentially to WhatsApp.
     */
    private fun showSelectPacksToWhatsAppDialog() {
        val allPacks = StickerStorage.getAllPacks(this)
        val readyPacks = allPacks.filter { it.stickers.size in 3..30 }

        if (readyPacks.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Sin paquetes listos")
                .setMessage("No tienes paquetes con al menos 3 stickers preparados para WhatsApp.\n\nCrea o agrega más stickers a tus paquetes primero.")
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }

        val dialogBinding = DialogSelectPacksWhatsappBinding.inflate(layoutInflater)
        val selectedIds = readyPacks.map { it.identifier }.toMutableSet()

        fun updateCounter() {
            dialogBinding.tvSelectedCounter.text = "${selectedIds.size} / ${readyPacks.size} seleccionados"
            dialogBinding.btnConfirmAddSequential.text = "🚀 Añadir (${selectedIds.size}) a WhatsApp Seguidamente"
            dialogBinding.btnConfirmAddSequential.isEnabled = selectedIds.isNotEmpty()
            dialogBinding.btnConfirmAddSequential.alpha = if (selectedIds.isNotEmpty()) 1.0f else 0.5f
            dialogBinding.cbSelectAll.isChecked = selectedIds.size == readyPacks.size
        }

        dialogBinding.recyclerViewSelectPacks.layoutManager = LinearLayoutManager(this)
        dialogBinding.recyclerViewSelectPacks.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemBinding = ItemPackSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                return object : RecyclerView.ViewHolder(itemBinding.root) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val pack = readyPacks[position]
                val itemBinding = ItemPackSelectBinding.bind(holder.itemView)

                itemBinding.tvPackName.text = pack.name
                val animBadge = if (pack.animatedStickerPack) " • 🎬 Animado" else " • 🖼️ Estático"
                itemBinding.tvPackSubtitle.text = "${pack.stickers.size} stickers$animBadge"

                val isWhitelisted = WhatsAppChecker.isPackWhitelisted(this@MainActivity, pack.identifier)
                if (isWhitelisted) {
                    itemBinding.tvPackStatus.text = "Ya en WhatsApp"
                    itemBinding.tvPackStatus.setTextColor(getColor(R.color.badge_success))
                } else {
                    itemBinding.tvPackStatus.text = "Listo para añadir"
                    itemBinding.tvPackStatus.setTextColor(getColor(R.color.accent_whatsapp))
                }

                if (pack.trayImageFile.isNotEmpty()) {
                    val trayFile = StickerStorage.getStickerFile(this@MainActivity, pack.identifier, pack.trayImageFile)
                    if (trayFile.exists()) {
                        Glide.with(this@MainActivity).load(trayFile).into(itemBinding.imgTrayIcon)
                    } else {
                        itemBinding.imgTrayIcon.setImageResource(R.drawable.ic_sticker_pack)
                    }
                } else {
                    itemBinding.imgTrayIcon.setImageResource(R.drawable.ic_sticker_pack)
                }

                itemBinding.cbSelect.isChecked = selectedIds.contains(pack.identifier)

                val toggle = {
                    if (selectedIds.contains(pack.identifier)) {
                        selectedIds.remove(pack.identifier)
                    } else {
                        selectedIds.add(pack.identifier)
                    }
                    itemBinding.cbSelect.isChecked = selectedIds.contains(pack.identifier)
                    updateCounter()
                }

                itemBinding.cbSelect.setOnClickListener { toggle() }
                itemBinding.root.setOnClickListener { toggle() }
            }

            override fun getItemCount(): Int = readyPacks.size
        }

        updateCounter()

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.cbSelectAll.setOnClickListener {
            if (dialogBinding.cbSelectAll.isChecked) {
                selectedIds.addAll(readyPacks.map { it.identifier })
            } else {
                selectedIds.clear()
            }
            dialogBinding.recyclerViewSelectPacks.adapter?.notifyDataSetChanged()
            updateCounter()
        }

        dialogBinding.btnConfirmAddSequential.setOnClickListener {
            val packsToAdd = readyPacks.filter { selectedIds.contains(it.identifier) }
            dialog.dismiss()
            startSequentialAddToWhatsApp(packsToAdd)
        }

        dialog.show()
    }

    /**
     * Queues multiple packs to be added sequentially to WhatsApp.
     * WhatsApp requires user confirmation per pack; this sequentially triggers each pack dialog!
     */
    private fun startSequentialAddToWhatsApp(packsToAdd: List<StickerPack>) {
        if (packsToAdd.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            StickerStorage.sanitizeAndNormalizePacks(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (packsToAdd.size == 1) {
                    sequentialAddQueue.clear()
                    totalSequentialCount = 1
                    currentSequentialIndex = 0
                    WhatsAppChecker.addPackToWhatsApp(this@MainActivity, packsToAdd.first(), ADD_PACK_REQUEST_CODE)
                    return@withContext
                }

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("📲 Añadir ${packsToAdd.size} Paquetes a WhatsApp")
                    .setMessage("Por seguridad, WhatsApp te pedirá confirmar cada paquete con un toque en su pantalla.\n\nLa app te abrirá automáticamente un paquete tras otro de forma continua.\n\n¿Deseas comenzar ahora?")
                    .setPositiveButton("Comenzar") { _, _ ->
                        sequentialAddQueue.clear()
                        sequentialAddQueue.addAll(packsToAdd)
                        totalSequentialCount = packsToAdd.size
                        currentSequentialIndex = 0
                        launchNextInWhatsAppQueue()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun launchNextInWhatsAppQueue() {
        if (sequentialAddQueue.isEmpty()) {
            showSequentialAddFinishedDialog()
            return
        }

        val pack = sequentialAddQueue.removeFirst()
        currentSequentialIndex++

        Toast.makeText(
            this,
            "Añadiendo ($currentSequentialIndex de $totalSequentialCount): '${pack.name}'",
            Toast.LENGTH_SHORT
        ).show()

        WhatsAppChecker.addPackToWhatsApp(this, pack, ADD_PACK_REQUEST_CODE)
    }

    private fun showSequentialAddFinishedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("🎉 ¡Proceso Finalizado!")
            .setMessage("Se procesaron los $totalSequentialCount paquetes para WhatsApp.\n\n¡Abre cualquier chat en WhatsApp y verás tus nuevos paquetes de stickers listos para usar!")
            .setPositiveButton("Aceptar") { _, _ ->
                loadPacks()
            }
            .show()
        totalSequentialCount = 0
        currentSequentialIndex = 0
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ADD_PACK_REQUEST_CODE) {
            val error = data?.getStringExtra("validation_error")
            android.util.Log.d("MainActivity", "onActivityResult de WhatsApp: resultCode=$resultCode, error=$error")

            if (resultCode == RESULT_OK) {
                if (sequentialAddQueue.isNotEmpty()) {
                    Toast.makeText(this, "✅ Añadido con éxito. Abriendo siguiente...", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        delay(450)
                        launchNextInWhatsAppQueue()
                    }
                } else if (totalSequentialCount > 1) {
                    showSequentialAddFinishedDialog()
                } else {
                    Toast.makeText(this, "¡Paquete añadido a WhatsApp con éxito!", Toast.LENGTH_SHORT).show()
                }
            } else {
                if (sequentialAddQueue.isNotEmpty()) {
                    val remainingCount = sequentialAddQueue.size
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Adición interrumpida")
                        .setMessage("No se confirmó el paquete en WhatsApp. ¿Deseas continuar añadiendo los siguientes $remainingCount paquetes?")
                        .setPositiveButton("Continuar con el siguiente") { _, _ ->
                            launchNextInWhatsAppQueue()
                        }
                        .setNegativeButton("Detener") { _, _ ->
                            sequentialAddQueue.clear()
                            totalSequentialCount = 0
                            currentSequentialIndex = 0
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    if (!error.isNullOrEmpty()) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("WhatsApp reportó un problema")
                            .setMessage("WhatsApp no pudo procesar el paquete:\n\n$error")
                            .setPositiveButton("Aceptar", null)
                            .show()
                    }
                }
            }
            loadPacks()
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

    private fun loadPacks() {
        val packs = StickerStorage.getAllPacks(this)
        packsList.clear()
        packsList.addAll(packs)
        adapter.notifyDataSetChanged()

        if (packs.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.recyclerViewPacks.visibility = View.GONE
            binding.fabCreatePack.visibility = View.GONE
            exitSelectionMode()
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.recyclerViewPacks.visibility = View.VISIBLE
            if (!adapter.isSelectionMode) {
                binding.fabCreatePack.visibility = View.VISIBLE
            }
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

    private fun checkAndScanWhatsAppStickers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Acceso a Stickers de WhatsApp")
                    .setMessage("Para escanear directamente los stickers que recibes y guardas en WhatsApp, Android requiere conceder el permiso de acceso a archivos.\n\nToca en 'Conceder Permiso' y habilita el interruptor para esta app.")
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
            return
        }

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Escaneando WhatsApp Stickers")
            .setMessage("Buscando stickers .webp en WhatsApp...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            val allFiles = mutableListOf<File>()
            for (dir in targetDirs) {
                dir.listFiles()?.filter {
                    it.isFile && it.name.endsWith(".webp", ignoreCase = true)
                }?.let { allFiles.addAll(it) }
            }

            if (allFiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "No se encontraron stickers .webp en WhatsApp", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // Inspect and filter duplicates
            val staticFiles = mutableListOf<File>()
            val animatedFiles = mutableListOf<File>()
            val (existingPayloads, existingVisuals) = StickerStorage.getAllExistingStickerSignatures(this@MainActivity)
            val seenPayloads = mutableSetOf<String>()
            val seenVisuals = mutableSetOf<String>()
            var duplicatesCount = 0

            for ((index, file) in allFiles.withIndex()) {
                val uri = Uri.fromFile(file)
                val inspection = ImageProcessor.inspectWebP(this@MainActivity, uri)
                if (inspection.isValidWebP) {
                    val isDup = (inspection.payloadHash.isNotEmpty() && (existingPayloads.contains(inspection.payloadHash) || seenPayloads.contains(inspection.payloadHash))) ||
                                (inspection.visualDHash.isNotEmpty() && (existingVisuals.contains(inspection.visualDHash) || seenVisuals.contains(inspection.visualDHash)))

                    if (isDup) {
                        duplicatesCount++
                    } else {
                        if (inspection.payloadHash.isNotEmpty()) seenPayloads.add(inspection.payloadHash)
                        if (inspection.visualDHash.isNotEmpty()) seenVisuals.add(inspection.visualDHash)
                        if (inspection.isAnimated) {
                            animatedFiles.add(file)
                        } else {
                            staticFiles.add(file)
                        }
                    }
                }
            }

            val totalNew = staticFiles.size + animatedFiles.size
            if (totalNew == 0) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Stickers ya importados")
                        .setMessage("Todos los $duplicatesCount stickers encontrados en WhatsApp ya los tienes guardados en tus paquetes.")
                        .setPositiveButton("Aceptar", null)
                        .show()
                }
                return@launch
            }

            // Partition into packs of 3..30
            val staticChunks = partitionIntoValidPacks(staticFiles)
            val animatedChunks = partitionIntoValidPacks(animatedFiles)
            var createdPacksCount = 0

            fun saveChunks(chunks: List<List<File>>, isAnimated: Boolean, baseName: String) {
                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    val packName = if (chunks.size == 1) {
                        baseName
                    } else if (chunkIndex == 0) {
                        baseName
                    } else {
                        "$baseName (Parte $chunkIndex)"
                    }

                    val packId = UUID.randomUUID().toString()
                    val stickers = mutableListOf<Sticker>()
                    var trayImage = ""

                    for (file in chunk) {
                        val fileName = "${UUID.randomUUID()}.webp"
                        val destFile = StickerStorage.getStickerFile(this@MainActivity, packId, fileName)
                        try {
                            file.copyTo(destFile, overwrite = true)
                            stickers.add(Sticker(imageFileName = fileName, emojis = listOf("⭐")))
                            if (trayImage.isEmpty()) {
                                trayImage = fileName
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (stickers.isNotEmpty()) {
                        val pack = StickerPack(
                            identifier = packId,
                            name = packName,
                            publisher = "WhatsApp",
                            trayImageFile = trayImage,
                            animatedStickerPack = isAnimated,
                            stickers = stickers
                        )
                        StickerStorage.savePack(this@MainActivity, pack)
                        createdPacksCount++
                    }
                }
            }

            if (staticChunks.isNotEmpty()) {
                saveChunks(staticChunks, false, "Mis Stickers")
            }
            if (animatedChunks.isNotEmpty()) {
                saveChunks(animatedChunks, true, "Mis Stickers Animados")
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                loadPacks()
                val summary = buildString {
                    appendLine("🎉 ¡Se crearon $createdPacksCount paquetes con $totalNew stickers de WhatsApp!")
                    if (duplicatesCount > 0) {
                        appendLine("\n🧹 $duplicatesCount stickers ya existentes fueron omitidos.")
                    }
                    appendLine("\nCada paquete tiene un máximo de 30 stickers para cumplir con WhatsApp.")
                }
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("✅ Escaneo Completado")
                    .setMessage(summary)
                    .setPositiveButton("Aceptar", null)
                    .show()
            }
        }
    }
}
