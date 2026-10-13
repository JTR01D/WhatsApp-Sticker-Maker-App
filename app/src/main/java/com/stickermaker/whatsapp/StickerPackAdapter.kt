package com.stickermaker.whatsapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.stickermaker.whatsapp.databinding.ItemPackBinding
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.StickerStorage
import com.stickermaker.whatsapp.utils.WhatsAppChecker

class StickerPackAdapter(
    private val packs: MutableList<StickerPack>,
    private val onPackClick: (StickerPack) -> Unit,
    private val onAddToWhatsAppClick: (StickerPack) -> Unit,
    private val onDeleteClick: (StickerPack) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<StickerPackAdapter.PackViewHolder>() {

    var isSelectionMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                selectedPackIds.clear()
            }
            notifyDataSetChanged()
        }

    val selectedPackIds = mutableSetOf<String>()

    fun selectAll(context: android.content.Context) {
        selectedPackIds.clear()
        val selectable = packs.filter { !WhatsAppChecker.isPackWhitelisted(context, it.identifier) }
        selectedPackIds.addAll(selectable.map { it.identifier })
        onSelectionChanged(selectedPackIds.size)
        notifyDataSetChanged()
    }

    fun deselectAll() {
        selectedPackIds.clear()
        onSelectionChanged(0)
        notifyDataSetChanged()
    }

    inner class PackViewHolder(private val binding: ItemPackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pack: StickerPack) {
            binding.tvPackName.text = pack.name
            val typeBadge = if (pack.animatedStickerPack) " • 🎬 Animado" else ""
            binding.tvStickerCount.text = "${pack.stickers.size} / 30 stickers$typeBadge"

            // Tray icon loading
            val context = binding.root.context
            if (pack.trayImageFile.isNotEmpty()) {
                val trayFile = StickerStorage.getStickerFile(context, pack.identifier, pack.trayImageFile)
                if (trayFile.exists()) {
                    Glide.with(context).load(trayFile).into(binding.imgTrayIcon)
                } else {
                    binding.imgTrayIcon.setImageResource(R.drawable.ic_sticker_pack)
                }
            } else {
                binding.imgTrayIcon.setImageResource(R.drawable.ic_sticker_pack)
            }

            // Thumbnail previews (up to 4)
            val thumbs = listOf(binding.thumb1, binding.thumb2, binding.thumb3, binding.thumb4)
            for (i in thumbs.indices) {
                if (i < pack.stickers.size) {
                    val sticker = pack.stickers[i]
                    val stickerFile = StickerStorage.getStickerFile(context, pack.identifier, sticker.imageFileName)
                    if (stickerFile.exists()) {
                        thumbs[i].visibility = View.VISIBLE
                        Glide.with(context).load(stickerFile).into(thumbs[i])
                    } else {
                        thumbs[i].visibility = View.GONE
                    }
                } else {
                    thumbs[i].visibility = View.GONE
                }
            }

            // Check if already in WhatsApp
            val isWhitelisted = WhatsAppChecker.isPackWhitelisted(context, pack.identifier)
            if (isWhitelisted) {
                binding.btnAddToWhatsApp.text = context.getString(R.string.added_to_whatsapp)
                binding.btnAddToWhatsApp.setIconResource(R.drawable.ic_check)
                binding.btnAddToWhatsApp.isEnabled = false
                binding.btnAddToWhatsApp.setBackgroundColor(ContextCompat.getColor(context, R.color.badge_success))
            } else {
                binding.btnAddToWhatsApp.text = context.getString(R.string.add_to_whatsapp)
                binding.btnAddToWhatsApp.setIconResource(R.drawable.ic_whatsapp)
                binding.btnAddToWhatsApp.isEnabled = true
                binding.btnAddToWhatsApp.setBackgroundColor(ContextCompat.getColor(context, R.color.accent_whatsapp))
            }

            if (isSelectionMode) {
                binding.btnDeletePack.visibility = View.GONE

                if (isWhitelisted) {
                    binding.root.alpha = 0.55f
                    binding.cbSelectPack.visibility = View.VISIBLE
                    binding.cbSelectPack.isEnabled = false
                    binding.cbSelectPack.isChecked = false
                    binding.cbSelectPack.alpha = 0.35f
                    binding.btnAddToWhatsApp.visibility = View.VISIBLE
                    binding.btnAddToWhatsApp.isEnabled = false

                    val showAlreadyAddedToast = {
                        Toast.makeText(context, "Este paquete ya está añadido a WhatsApp", Toast.LENGTH_SHORT).show()
                    }
                    binding.cbSelectPack.setOnClickListener { showAlreadyAddedToast() }
                    binding.root.setOnClickListener { showAlreadyAddedToast() }
                } else {
                    binding.root.alpha = 1.0f
                    binding.cbSelectPack.visibility = View.VISIBLE
                    binding.cbSelectPack.isEnabled = true
                    binding.cbSelectPack.alpha = 1.0f
                    binding.cbSelectPack.isChecked = selectedPackIds.contains(pack.identifier)
                    binding.btnAddToWhatsApp.visibility = View.GONE

                    val toggleSelect = {
                        if (selectedPackIds.contains(pack.identifier)) {
                            selectedPackIds.remove(pack.identifier)
                        } else {
                            selectedPackIds.add(pack.identifier)
                        }
                        binding.cbSelectPack.isChecked = selectedPackIds.contains(pack.identifier)
                        onSelectionChanged(selectedPackIds.size)
                    }

                    binding.cbSelectPack.setOnClickListener { toggleSelect() }
                    binding.root.setOnClickListener { toggleSelect() }
                }
            } else {
                binding.root.alpha = 1.0f
                binding.cbSelectPack.visibility = View.GONE
                binding.cbSelectPack.isEnabled = true
                binding.cbSelectPack.alpha = 1.0f
                binding.btnDeletePack.visibility = View.VISIBLE
                binding.btnAddToWhatsApp.visibility = View.VISIBLE

                binding.root.setOnClickListener { onPackClick(pack) }
                binding.btnAddToWhatsApp.setOnClickListener { onAddToWhatsAppClick(pack) }
                binding.btnDeletePack.setOnClickListener { onDeleteClick(pack) }

                binding.root.setOnLongClickListener {
                    if (isWhitelisted) {
                        Toast.makeText(context, "Este paquete ya está añadido a WhatsApp", Toast.LENGTH_SHORT).show()
                        true
                    } else {
                        isSelectionMode = true
                        selectedPackIds.add(pack.identifier)
                        onSelectionChanged(selectedPackIds.size)
                        true
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PackViewHolder {
        val binding = ItemPackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PackViewHolder, position: Int) {
        holder.bind(packs[position])
    }

    override fun getItemCount(): Int = packs.size

    fun updateList(newPacks: List<StickerPack>) {
        packs.clear()
        packs.addAll(newPacks)
        notifyDataSetChanged()
    }
}
