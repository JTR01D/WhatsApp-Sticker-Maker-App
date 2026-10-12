package com.stickermaker.whatsapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    private val onDeleteClick: (StickerPack) -> Unit
) : RecyclerView.Adapter<StickerPackAdapter.PackViewHolder>() {

    inner class PackViewHolder(private val binding: ItemPackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(pack: StickerPack) {
            binding.tvPackName.text = pack.name
            binding.tvPublisher.text = "Por ${pack.publisher}"
            binding.tvStickerCount.text = "${pack.stickers.size} / 30 stickers"

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
                    thumbs[i].visibility = View.VISIBLE
                    Glide.with(context).load(stickerFile).into(thumbs[i])
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

            binding.root.setOnClickListener { onPackClick(pack) }
            binding.btnAddToWhatsApp.setOnClickListener { onAddToWhatsAppClick(pack) }
            binding.btnDeletePack.setOnClickListener { onDeleteClick(pack) }
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
