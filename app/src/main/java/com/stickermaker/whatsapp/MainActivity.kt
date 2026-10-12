package com.stickermaker.whatsapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stickermaker.whatsapp.databinding.ActivityMainBinding
import com.stickermaker.whatsapp.databinding.DialogCreatePackBinding
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.StickerStorage
import com.stickermaker.whatsapp.utils.WhatsAppChecker
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: StickerPackAdapter
    private val packsList = mutableListOf<StickerPack>()

    companion object {
        const val ADD_PACK_REQUEST_CODE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
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
            .setPositiveButton(R.string.create) { _, _ ->
                val name = dialogBinding.etPackName.text?.toString()?.trim().orEmpty()
                val publisher = dialogBinding.etPublisher.text?.toString()?.trim().orEmpty()

                if (name.isEmpty()) {
                    Toast.makeText(this, "El nombre del paquete no puede estar vacío", Toast.LENGTH_SHORT).show()
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
            .setTitle(R.string.delete)
            .setMessage(R.string.delete_pack_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                StickerStorage.deletePack(this, pack.identifier)
                loadPacks()
                Toast.makeText(this, "Paquete eliminado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ADD_PACK_REQUEST_CODE) {
            loadPacks()
        }
    }
}
