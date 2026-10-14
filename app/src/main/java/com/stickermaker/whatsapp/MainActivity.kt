package com.stickermaker.whatsapp

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Context
import com.stickermaker.whatsapp.databinding.ActivityMainBinding
import com.stickermaker.whatsapp.databinding.DialogAboutMeBinding
import com.stickermaker.whatsapp.databinding.DialogCreatePackBinding
import com.stickermaker.whatsapp.databinding.DialogExportZipProgressBinding
import com.stickermaker.whatsapp.databinding.DialogExportZipSuccessBinding
import com.stickermaker.whatsapp.databinding.DialogLanguageSelectorBinding
import com.stickermaker.whatsapp.databinding.DialogMainMoreOptionsBinding
import com.stickermaker.whatsapp.databinding.DialogSelectPacksWhatsappBinding
import com.stickermaker.whatsapp.databinding.ItemPackSelectBinding
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.LocaleHelper
import com.stickermaker.whatsapp.utils.StickerStorage
import com.stickermaker.whatsapp.utils.WhatsAppChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
            setDjiActiveTab(0)
            if (uri != null) {
                importZipFile(uri)
            }
        }

    private var pendingExportZipFile: File? = null

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null && pendingExportZipFile != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        pendingExportZipFile!!.inputStream().use { it.copyTo(out) }
                    }
                    Toast.makeText(this, "✅ Colección ZIP guardada correctamente", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error al guardar archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private fun applyLiquidGlassDialog(dialog: androidx.appcompat.app.AlertDialog) {
        dialog.window?.let { window ->
            window.setBackgroundDrawableResource(android.R.color.transparent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.apply {
                    blurBehindRadius = 40
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLanguageContext(newBase))
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
                R.id.action_more_options -> {
                    showMoreOptionsMenuDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun showMoreOptionsMenuDialog() {
        val optionsBinding = DialogMainMoreOptionsBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(optionsBinding.root)
            .create()
        applyLiquidGlassDialog(dialog)
        dialog.setOnDismissListener {
            setDjiActiveTab(0)
        }

        // 1. Guardar en Dispositivo (.zip)
        optionsBinding.cardOptionSaveZip.setOnClickListener {
            dialog.dismiss()
            shareAllPacksAsZip()
        }

        // 2. Importar Paquete (.zip)
        optionsBinding.cardOptionImportZip.setOnClickListener {
            dialog.dismiss()
            launchZipPicker()
        }

        // 3. Selector de Idioma / Language
        val currentLang = LocaleHelper.getLanguage(this)
        optionsBinding.tvLanguageBadge.text = currentLang.uppercase()
        optionsBinding.tvCurrentLanguage.text = if (currentLang == LocaleHelper.LANG_ES) {
            getString(R.string.lang_spanish)
        } else {
            getString(R.string.lang_english)
        }
        optionsBinding.cardOptionLanguage.setOnClickListener {
            dialog.dismiss()
            showLanguageSelectorDialog()
        }

        // 4. Acerca de / About Me
        optionsBinding.cardOptionAbout.setOnClickListener {
            dialog.dismiss()
            showAboutMeDialog()
        }

        // 5. Vaciar / Eliminar Todos los Paquetes
        optionsBinding.cardOptionDeleteAll.setOnClickListener {
            dialog.dismiss()
            confirmDeleteAllPacks()
        }

        // Botón Cerrar
        optionsBinding.btnCloseOptions.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAboutMeDialog() {
        val aboutBinding = DialogAboutMeBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(aboutBinding.root)
            .create()
        applyLiquidGlassDialog(dialog)

        aboutBinding.tvAboutVersion.text = "v${BuildConfig.VERSION_NAME} • Build ${BuildConfig.VERSION_CODE}"
        aboutBinding.btnAboutClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showLanguageSelectorDialog() {
        val langBinding = DialogLanguageSelectorBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(langBinding.root)
            .create()
        applyLiquidGlassDialog(dialog)

        val currentLang = LocaleHelper.getLanguage(this)
        if (currentLang == LocaleHelper.LANG_ES) {
            langBinding.ivCheckSpanish.visibility = View.VISIBLE
            langBinding.ivCheckEnglish.visibility = View.GONE
        } else {
            langBinding.ivCheckSpanish.visibility = View.GONE
            langBinding.ivCheckEnglish.visibility = View.VISIBLE
        }

        langBinding.cardLangSpanish.setOnClickListener {
            dialog.dismiss()
            if (currentLang != LocaleHelper.LANG_ES) {
                LocaleHelper.setLocale(this, LocaleHelper.LANG_ES)
            }
        }

        langBinding.cardLangEnglish.setOnClickListener {
            dialog.dismiss()
            if (currentLang != LocaleHelper.LANG_EN) {
                LocaleHelper.setLocale(this, LocaleHelper.LANG_EN)
            }
        }

        langBinding.btnCancelLang.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

    private fun setDjiActiveTab(tabIndex: Int) {
        val colorActive = getColor(R.color.white)
        val colorInactive = android.graphics.Color.parseColor("#94A3B8")

        binding.indicatorDjiHome.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
        binding.iconDjiHome.imageTintList = android.content.res.ColorStateList.valueOf(if (tabIndex == 0) colorActive else colorInactive)

        binding.indicatorDjiWhatsApp.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
        binding.iconDjiWhatsApp.imageTintList = android.content.res.ColorStateList.valueOf(if (tabIndex == 1) colorActive else colorInactive)

        binding.indicatorDjiZip.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
        binding.iconDjiZip.imageTintList = android.content.res.ColorStateList.valueOf(if (tabIndex == 2) colorActive else colorInactive)

        binding.indicatorDjiProfile.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
        binding.iconDjiProfile.imageTintList = android.content.res.ColorStateList.valueOf(if (tabIndex == 3) colorActive else colorInactive)
    }

    private fun setupListeners() {
        // Floating Bottom Navigation Dock (DJI Mimo Style)
        binding.tabDjiHome.setOnClickListener {
            setDjiActiveTab(0)
            if (packsList.isNotEmpty()) {
                binding.recyclerViewPacks.smoothScrollToPosition(0)
            }
        }

        binding.tabDjiWhatsApp.setOnClickListener {
            setDjiActiveTab(1)
            checkAndScanWhatsAppStickers()
        }

        binding.tabDjiZip.setOnClickListener {
            setDjiActiveTab(2)
            launchZipPicker()
        }

        binding.tabDjiProfile.setOnClickListener {
            setDjiActiveTab(3)
            showMoreOptionsMenuDialog()
        }

        binding.btnDjiAction.setOnClickListener {
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
            val selectableCount = packsList.count { !WhatsAppChecker.isPackWhitelisted(this, it.identifier) }
            if (adapter.selectedPackIds.size >= selectableCount && selectableCount > 0) {
                adapter.deselectAll()
            } else {
                adapter.selectAll(this)
            }
        }

        binding.btnClosePacksSelection.setOnClickListener {
            exitSelectionMode()
        }

        binding.btnAddSelectedToWhatsApp.setOnClickListener {
            val selected = packsList.filter { adapter.selectedPackIds.contains(it.identifier) }
            val validToAdd = selected.filter {
                !WhatsAppChecker.isPackWhitelisted(this, it.identifier) && it.stickers.size in 3..30
            }

            if (validToAdd.isEmpty()) {
                Toast.makeText(this, "Selecciona paquetes con al menos 3 stickers que no estén ya en WhatsApp", Toast.LENGTH_SHORT).show()
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
            binding.layoutDjiBottomBar.visibility = View.GONE
            binding.tvSelectedPacksCount.text = if (selectedCount == 1) "1 seleccionado" else "$selectedCount seleccionados"
            binding.btnAddSelectedToWhatsApp.isEnabled = selectedCount > 0
            binding.btnAddSelectedToWhatsApp.alpha = if (selectedCount > 0) 1.0f else 0.5f
            binding.btnDeleteSelectedPacks.isEnabled = selectedCount > 0
            binding.btnDeleteSelectedPacks.alpha = if (selectedCount > 0) 1.0f else 0.5f

            val selectableCount = packsList.count { !WhatsAppChecker.isPackWhitelisted(this, it.identifier) }
            if (selectedCount >= selectableCount && selectableCount > 0) {
                binding.btnSelectAllPacks.text = "Ninguno"
            } else {
                binding.btnSelectAllPacks.text = "Todos"
            }
        } else {
            binding.cardPacksSelectionBar.visibility = View.GONE
            binding.layoutDjiBottomBar.visibility = View.VISIBLE
        }
    }

    private fun confirmDeleteSelectedPacks() {
        val selectedIds = adapter.selectedPackIds.toList()
        if (selectedIds.isEmpty()) return

        val dialog = MaterialAlertDialogBuilder(this)
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
            .create()
        applyLiquidGlassDialog(dialog)
        dialog.show()
    }

    private fun confirmDeleteAllPacks() {
        val count = packsList.size
        if (count == 0) {
            Toast.makeText(this, "No hay paquetes creados para eliminar", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = MaterialAlertDialogBuilder(this)
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
            .create()
        applyLiquidGlassDialog(dialog)
        dialog.show()
    }

    private fun exitSelectionMode() {
        adapter.isSelectionMode = false
        binding.cardPacksSelectionBar.visibility = View.GONE
        binding.layoutDjiBottomBar.visibility = View.VISIBLE
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

        val pendingPacks = readyPacks.filter { !WhatsAppChecker.isPackWhitelisted(this, it.identifier) }
        if (pendingPacks.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("¡Todo al día! 🎉")
                .setMessage("Todos tus paquetes con al menos 3 stickers ya están añadidos a WhatsApp.")
                .setPositiveButton("Aceptar", null)
                .show()
            return
        }

        val dialogBinding = DialogSelectPacksWhatsappBinding.inflate(layoutInflater)
        val selectedIds = pendingPacks.map { it.identifier }.toMutableSet()

        fun updateCounter() {
            dialogBinding.tvSelectedCounter.text = "${selectedIds.size} / ${pendingPacks.size} seleccionados"
            dialogBinding.btnConfirmAddSequential.text = "🚀 Añadir (${selectedIds.size}) a WhatsApp Seguidamente"
            dialogBinding.btnConfirmAddSequential.isEnabled = selectedIds.isNotEmpty()
            dialogBinding.btnConfirmAddSequential.alpha = if (selectedIds.isNotEmpty()) 1.0f else 0.5f
            dialogBinding.cbSelectAll.isChecked = selectedIds.size == pendingPacks.size && pendingPacks.isNotEmpty()
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
                    itemBinding.cbSelect.isEnabled = false
                    itemBinding.cbSelect.isChecked = false
                    itemBinding.cbSelect.alpha = 0.35f
                    itemBinding.root.alpha = 0.55f

                    val alreadyAdded = {
                        Toast.makeText(this@MainActivity, "Este paquete ya está añadido a WhatsApp", Toast.LENGTH_SHORT).show()
                    }
                    itemBinding.cbSelect.setOnClickListener { alreadyAdded() }
                    itemBinding.root.setOnClickListener { alreadyAdded() }
                } else {
                    itemBinding.tvPackStatus.text = "Listo para añadir"
                    itemBinding.tvPackStatus.setTextColor(getColor(R.color.accent_whatsapp))
                    itemBinding.cbSelect.isEnabled = true
                    itemBinding.cbSelect.alpha = 1.0f
                    itemBinding.root.alpha = 1.0f
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
            }

            override fun getItemCount(): Int = readyPacks.size
        }

        updateCounter()

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        applyLiquidGlassDialog(dialog)

        dialogBinding.cbSelectAll.setOnClickListener {
            if (dialogBinding.cbSelectAll.isChecked) {
                selectedIds.addAll(pendingPacks.map { it.identifier })
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
            val refreshedPacks = packsToAdd.map { p ->
                StickerStorage.getPack(this@MainActivity, p.identifier) ?: p
            }
            withContext(Dispatchers.Main) {
                if (refreshedPacks.size == 1) {
                    sequentialAddQueue.clear()
                    totalSequentialCount = 1
                    currentSequentialIndex = 0
                    WhatsAppChecker.addPackToWhatsApp(this@MainActivity, refreshedPacks.first(), ADD_PACK_REQUEST_CODE)
                    return@withContext
                }

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("📲 Añadir ${refreshedPacks.size} Paquetes a WhatsApp")
                    .setMessage("Por seguridad, WhatsApp te pedirá confirmar cada paquete con un toque en su pantalla.\n\nLa app te abrirá automáticamente un paquete tras otro de forma continua.\n\n¿Deseas comenzar ahora?")
                    .setPositiveButton("Comenzar") { _, _ ->
                        sequentialAddQueue.clear()
                        sequentialAddQueue.addAll(refreshedPacks)
                        totalSequentialCount = refreshedPacks.size
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
            Toast.makeText(this, "No tienes paquetes creados para exportar", Toast.LENGTH_SHORT).show()
            return
        }

        val progressBinding = DialogExportZipProgressBinding.inflate(layoutInflater)
        progressBinding.progressExport.isIndeterminate = false
        progressBinding.progressExport.progress = 0
        progressBinding.tvExportCounter.text = "0 / ${packs.size} paquetes"
        progressBinding.tvExportPercentage.text = "0%"
        progressBinding.tvExportPackStatus.text = "Preparando paquetes..."

        val progressDialog = MaterialAlertDialogBuilder(this)
            .setView(progressBinding.root)
            .setCancelable(false)
            .create()
        applyLiquidGlassDialog(progressDialog)
        progressDialog.show()

        var isCancelled = false
        var exportJob: Job? = null

        progressBinding.btnCancelExport.setOnClickListener {
            isCancelled = true
            exportJob?.cancel()
            progressDialog.dismiss()
            Toast.makeText(this@MainActivity, "Exportación cancelada", Toast.LENGTH_SHORT).show()
        }

        exportJob = lifecycleScope.launch(Dispatchers.IO) {
            val zipFile = StickerStorage.exportAllPacksToZip(
                context = this@MainActivity,
                onProgress = { current, total, packName ->
                    if (isCancelled) return@exportAllPacksToZip
                    val percent = if (total > 0) (current * 100) / total else 0
                    runOnUiThread {
                        if (!isCancelled) {
                            progressBinding.progressExport.progress = percent
                            progressBinding.tvExportPercentage.text = "$percent%"
                            progressBinding.tvExportCounter.text = "$current / $total paquetes"
                            progressBinding.tvExportPackStatus.text = "Empaquetando: $packName"
                        }
                    }
                },
                isCancelled = { isCancelled }
            )

            withContext(Dispatchers.Main) {
                if (isCancelled) return@withContext
                progressDialog.dismiss()
                if (zipFile != null && zipFile.exists()) {
                    showExportZipSuccessDialog(zipFile, packs.size)
                } else {
                    Toast.makeText(this@MainActivity, "No se pudo generar el archivo ZIP de la colección", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showExportZipSuccessDialog(zipFile: File, totalPacks: Int) {
        val successBinding = DialogExportZipSuccessBinding.inflate(layoutInflater)
        val fileSizeMb = String.format(java.util.Locale.US, "%.1f MB", zipFile.length() / (1024.0 * 1024.0))
        successBinding.tvZipDetails.text = "$totalPacks paquetes • $fileSizeMb"
        successBinding.tvZipFileName.text = zipFile.name

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(successBinding.root)
            .create()
        applyLiquidGlassDialog(dialog)

        // 1. Guardar directo en Descargas del dispositivo
        successBinding.btnSaveToDownloads.setOnClickListener {
            val savedUri = StickerStorage.saveZipToDownloads(this, zipFile)
            if (savedUri != null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("✅ Guardado en Descargas")
                    .setMessage("Tu colección se guardó exitosamente en la carpeta Descargas de tu teléfono:\n\n📁 Download/${zipFile.name}\n\n($fileSizeMb)")
                    .setPositiveButton("Aceptar", null)
                    .show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, "No se pudo guardar automáticamente. Intenta 'Elegir otra carpeta'", Toast.LENGTH_LONG).show()
            }
        }

        // 2. Elegir carpeta con SAF (Storage Access Framework)
        successBinding.btnSaveCustomFolder.setOnClickListener {
            pendingExportZipFile = zipFile
            createDocumentLauncher.launch(zipFile.name)
            dialog.dismiss()
        }

        // 3. Compartir por Apps (WhatsApp, etc.)
        successBinding.btnShareViaApps.setOnClickListener {
            dialog.dismiss()
            try {
                val authority = "${applicationContext.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(this, authority, zipFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Colección completa de Stickers")
                    putExtra(Intent.EXTRA_TEXT, "¡Aquí tienes mi colección completa de stickers ($totalPacks paquetes) para WhatsApp!")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Compartir todos los paquetes por WhatsApp"))
            } catch (e: Exception) {
                Toast.makeText(this, "Error al compartir: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        successBinding.btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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

    private var hasCheckedStartupDedup = false

    override fun onResume() {
        super.onResume()
        if (!hasCheckedStartupDedup) {
            hasCheckedStartupDedup = true
            val prefs = getSharedPreferences("sticker_maker_preferences", Context.MODE_PRIVATE)
            val alreadyDeduped = prefs.getBoolean("has_completed_startup_dedup_v138", false)
            if (!alreadyDeduped) {
                prefs.edit().putBoolean("has_completed_startup_dedup_v138", true).apply()
                lifecycleScope.launch(Dispatchers.IO) {
                    val removed = StickerStorage.deduplicatePackStickers(this@MainActivity)
                    if (removed > 0) {
                        android.util.Log.i("MainActivity", "Startup dedup removed $removed duplicate stickers")
                        withContext(Dispatchers.Main) {
                            loadPacks()
                        }
                    }
                }
            }
        }
        loadPacks()
        setDjiActiveTab(0)
    }

    private fun loadPacks() {
        val packs = StickerStorage.getAllPacks(this)
        packsList.clear()
        packsList.addAll(packs)
        adapter.notifyDataSetChanged()

        if (packs.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.recyclerViewPacks.visibility = View.GONE
            binding.layoutDjiBottomBar.visibility = if (adapter.isSelectionMode) View.GONE else View.VISIBLE
            exitSelectionMode()
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.recyclerViewPacks.visibility = View.VISIBLE
            if (!adapter.isSelectionMode) {
                binding.layoutDjiBottomBar.visibility = View.VISIBLE
            }
        }
    }

    private fun showCreatePackDialog() {
        val dialogBinding = DialogCreatePackBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        applyLiquidGlassDialog(dialog)

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnCreatePack.setOnClickListener {
            val name = dialogBinding.etPackName.text?.toString()?.trim().orEmpty()
            val publisher = dialogBinding.etPublisher.text?.toString()?.trim().orEmpty()

            if (name.isEmpty()) {
                dialogBinding.tilPackName.error = "Escribe un nombre para el paquete"
                dialogBinding.etPackName.requestFocus()
                return@setOnClickListener
            }
            dialogBinding.tilPackName.error = null

            val finalPublisher = if (publisher.isEmpty()) "Personal" else publisher
            val identifier = UUID.randomUUID().toString()

            val newPack = StickerPack(
                identifier = identifier,
                name = name,
                publisher = finalPublisher,
                trayImageFile = ""
            )

            StickerStorage.savePack(this, newPack)
            dialog.dismiss()
            openPackDetail(identifier)
        }

        dialog.show()
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
