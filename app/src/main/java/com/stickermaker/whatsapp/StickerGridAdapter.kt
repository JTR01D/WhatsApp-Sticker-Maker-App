package com.stickermaker.whatsapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.stickermaker.whatsapp.databinding.ItemStickerBinding
import com.stickermaker.whatsapp.model.Sticker
import com.stickermaker.whatsapp.utils.StickerStorage

class StickerGridAdapter(
    private val packId: String,
    private val stickers: MutableList<Sticker>,
    private var trayFileName: String = "",
    private val onStickerClick: (Sticker) -> Unit,
    private val onDeleteStickerClick: (Sticker) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<StickerGridAdapter.StickerViewHolder>() {

    var isSelectionMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                selectedStickerNames.clear()
            }
            notifyDataSetChanged()
        }

    val selectedStickerNames = mutableSetOf<String>()

    fun setTrayFileName(name: String) {
        trayFileName = name
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedStickerNames.clear()
        selectedStickerNames.addAll(stickers.map { it.imageFileName })
        onSelectionChanged(selectedStickerNames.size)
        notifyDataSetChanged()
    }

    fun deselectAll() {
        selectedStickerNames.clear()
        onSelectionChanged(0)
        notifyDataSetChanged()
    }

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

            // Check if this sticker is currently the tray icon
            val isTray = trayFileName.isNotEmpty() && (
                trayFileName == sticker.imageFileName ||
                trayFileName == "tray_${sticker.imageFileName}" ||
                trayFileName == "tray_${packId}.webp" && stickers.firstOrNull()?.imageFileName == sticker.imageFileName
            )
            binding.imgIsTrayBadge.visibility = if (isTray) View.VISIBLE else View.GONE

            if (isSelectionMode) {
                binding.cbSelectSticker.visibility = View.VISIBLE
                binding.btnDeleteSticker.visibility = View.GONE
                binding.cbSelectSticker.isChecked = selectedStickerNames.contains(sticker.imageFileName)

                val toggleSelect = {
                    if (selectedStickerNames.contains(sticker.imageFileName)) {
                        selectedStickerNames.remove(sticker.imageFileName)
                    } else {
                        selectedStickerNames.add(sticker.imageFileName)
                    }
                    binding.cbSelectSticker.isChecked = selectedStickerNames.contains(sticker.imageFileName)
                    onSelectionChanged(selectedStickerNames.size)
                }

                binding.cbSelectSticker.setOnClickListener { toggleSelect() }
                binding.root.setOnClickListener { toggleSelect() }
            } else {
                binding.cbSelectSticker.visibility = View.GONE
                binding.btnDeleteSticker.visibility = View.VISIBLE

                binding.btnDeleteSticker.setOnClickListener {
                    onDeleteStickerClick(sticker)
                }

                binding.root.setOnClickListener {
                    onStickerClick(sticker)
                }

                binding.root.setOnLongClickListener {
                    isSelectionMode = true
                    selectedStickerNames.add(sticker.imageFileName)
                    onSelectionChanged(selectedStickerNames.size)
                    true
                }
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

    fun updateList(newStickers: List<Sticker>, currentTray: String = trayFileName) {
        stickers.clear()
        stickers.addAll(newStickers)
        trayFileName = currentTray
        notifyDataSetChanged()
    }
}
