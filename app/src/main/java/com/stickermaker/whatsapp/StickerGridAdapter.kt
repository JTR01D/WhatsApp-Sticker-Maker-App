package com.stickermaker.whatsapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.stickermaker.whatsapp.databinding.ItemStickerBinding
import com.stickermaker.whatsapp.model.Sticker
import com.stickermaker.whatsapp.utils.StickerStorage

class StickerGridAdapter(
    private val packId: String,
    private val stickers: MutableList<Sticker>,
    private val onDeleteStickerClick: (Sticker) -> Unit
) : RecyclerView.Adapter<StickerGridAdapter.StickerViewHolder>() {

    inner class StickerViewHolder(private val binding: ItemStickerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(sticker: Sticker) {
            val context = binding.root.context
            val stickerFile = StickerStorage.getStickerFile(context, packId, sticker.imageFileName)

            if (stickerFile.exists()) {
                Glide.with(context)
                    .load(stickerFile)
                    .into(binding.imgSticker)
            } else {
                binding.imgSticker.setImageResource(R.drawable.ic_sticker_pack)
            }

            binding.btnDeleteSticker.setOnClickListener {
                onDeleteStickerClick(sticker)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StickerViewHolder {
        val binding = ItemStickerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StickerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StickerViewHolder, position: Int) {
        holder.bind(stickers[position])
    }

    override fun getItemCount(): Int = stickers.size

    fun updateList(newStickers: List<Sticker>) {
        stickers.clear()
        stickers.addAll(newStickers)
        notifyDataSetChanged()
    }
}
