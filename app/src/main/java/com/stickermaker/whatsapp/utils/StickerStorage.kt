package com.stickermaker.whatsapp.utils

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.stickermaker.whatsapp.model.Sticker
import com.stickermaker.whatsapp.model.StickerPack
import java.io.File
import java.util.UUID

object StickerStorage {

    private const val PACKS_FILE = "sticker_packs.json"
    private const val STICKERS_DIR = "stickers"
    private val gson = Gson()

    private fun getJsonFile(context: Context): File {
        return File(context.filesDir, PACKS_FILE)
    }

    fun getPackDir(context: Context, packId: String): File {
        val dir = File(File(context.filesDir, STICKERS_DIR), packId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getStickerFile(context: Context, packId: String, fileName: String): File {
        return File(getPackDir(context, packId), fileName)
    }

    @Synchronized
    fun getAllPacks(context: Context): MutableList<StickerPack> {
        val file = getJsonFile(context)
        if (!file.exists()) return mutableListOf()

        return try {
            val json = file.readText()
            val listType = object : TypeToken<MutableList<StickerPack>>() {}.type
            gson.fromJson<MutableList<StickerPack>>(json, listType) ?: mutableListOf()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    @Synchronized
    fun getPack(context: Context, packId: String): StickerPack? {
        return getAllPacks(context).firstOrNull { it.identifier == packId }
    }

    @Synchronized
    fun savePacks(context: Context, packs: List<StickerPack>) {
        try {
            val json = gson.toJson(packs)
            getJsonFile(context).writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun savePack(context: Context, pack: StickerPack) {
        val packs = getAllPacks(context)
        val index = packs.indexOfFirst { it.identifier == pack.identifier }
        if (index != -1) {
            packs[index] = pack
        } else {
            packs.add(pack)
        }
        savePacks(context, packs)
    }

    @Synchronized
    fun deletePack(context: Context, packId: String) {
        val packs = getAllPacks(context)
        packs.removeAll { it.identifier == packId }
        savePacks(context, packs)

        // Delete directory and sticker files
        getPackDir(context, packId).deleteRecursively()
    }

    /**
     * Imports an image Uri into the specified pack, preserving animated WebP or formatting static WebP.
     */
    fun addStickerToPack(context: Context, packId: String, imageUri: Uri): Boolean {
        val pack = getPack(context, packId) ?: return false
        if (pack.stickers.size >= 30) return false // WhatsApp max limit

        val isAnimated = ImageProcessor.isAnimatedWebP(context, imageUri)

        // WhatsApp strict rule: a pack cannot mix static and animated stickers.
        // If pack is brand new (0 stickers), adopt the sticker's type.
        if (pack.stickers.isEmpty()) {
            pack.animatedStickerPack = isAnimated
        } else {
            if (pack.animatedStickerPack != isAnimated) {
                return false // Type mismatch
            }
        }

        val stickerFileName = "sticker_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.webp"
        val destinationFile = getStickerFile(context, packId, stickerFileName)

        val success = ImageProcessor.processStickerImage(context, imageUri, destinationFile, isAnimated)
        if (!success) return false

        // If pack doesn't have a tray icon yet, create one from this first sticker
        if (pack.trayImageFile.isEmpty()) {
            val trayFileName = "tray_${packId}.webp"
            val trayFile = getStickerFile(context, packId, trayFileName)
            if (ImageProcessor.generateTrayIcon(context, imageUri, trayFile)) {
                pack.trayImageFile = trayFileName
            }
        }

        pack.stickers.add(Sticker(imageFileName = stickerFileName, emojis = listOf("😀")))
        savePack(context, pack)
        return true
    }

    /**
     * Removes a sticker from the pack.
     */
    fun removeStickerFromPack(context: Context, packId: String, stickerFileName: String): Boolean {
        val pack = getPack(context, packId) ?: return false
        val removed = pack.stickers.removeAll { it.imageFileName == stickerFileName }
        if (removed) {
            val file = getStickerFile(context, packId, stickerFileName)
            if (file.exists()) file.delete()
            savePack(context, pack)
        }
        return removed
    }
}
