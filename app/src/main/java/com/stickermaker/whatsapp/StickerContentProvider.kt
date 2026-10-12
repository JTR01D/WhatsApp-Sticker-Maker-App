package com.stickermaker.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.StickerStorage
import java.io.File
import java.io.FileNotFoundException

class StickerContentProvider : ContentProvider() {

    companion object {
        private const val TAG = "StickerContentProvider"

        private const val METADATA = 1
        private const val METADATA_PACK = 2
        private const val STICKERS = 3
        private const val STICKERS_ASSET = 4
        private const val STICKER_PACK_TRAY_ICON = 5

        // Official WhatsApp Metadata columns
        private const val STICKER_PACK_IDENTIFIER = "sticker_pack_identifier"
        private const val STICKER_PACK_NAME = "sticker_pack_name"
        private const val STICKER_PACK_PUBLISHER = "sticker_pack_publisher"
        private const val STICKER_PACK_ICON = "sticker_pack_icon"
        private const val ANDROID_APP_DOWNLOAD_LINK = "android_play_store_link"
        private const val IOS_APP_DOWNLOAD_LINK = "ios_app_download_link"
        private const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        private const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        private const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        private const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        private const val IMAGE_DATA_VERSION = "image_data_version"
        private const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        private const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        // Official WhatsApp Sticker columns
        private const val STICKER_FILE_NAME = "sticker_file_name"
        private const val STICKER_FILE_EMOJI = "sticker_emoji"
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)

    override fun onCreate(): Boolean {
        val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY
        android.util.Log.d(TAG, "onCreate: Inicializando StickerContentProvider con authority=$authority")
        uriMatcher.addURI(authority, "metadata", METADATA)
        uriMatcher.addURI(authority, "metadata/*", METADATA_PACK)
        uriMatcher.addURI(authority, "stickers/*", STICKERS)
        uriMatcher.addURI(authority, "stickers_asset/*/*", STICKERS_ASSET)
        uriMatcher.addURI(authority, "stickers_asset/*", STICKER_PACK_TRAY_ICON)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val context = context ?: run {
            android.util.Log.e(TAG, "query: context es null")
            return null
        }
        val match = uriMatcher.match(uri)
        android.util.Log.d(TAG, "query recibido: uri=$uri, match=$match")

        return when (match) {
            METADATA -> {
                val packs = StickerStorage.getAllPacks(context).filter { it.isReadyForWhatsApp }
                android.util.Log.d(TAG, "query METADATA: devolviendo ${packs.size} paquetes listos")
                getMetadataCursor(packs)
            }
            METADATA_PACK -> {
                val packId = uri.lastPathSegment ?: run {
                    android.util.Log.e(TAG, "query METADATA_PACK: lastPathSegment es null para $uri")
                    return null
                }
                val pack = StickerStorage.getPack(context, packId) ?: run {
                    android.util.Log.e(TAG, "query METADATA_PACK: no se encontró paquete $packId")
                    return null
                }
                android.util.Log.d(TAG, "query METADATA_PACK: devolviendo metadata para ${pack.name} ($packId)")
                getMetadataCursor(listOf(pack))
            }
            STICKERS -> {
                val packId = uri.lastPathSegment ?: run {
                    android.util.Log.e(TAG, "query STICKERS: lastPathSegment es null para $uri")
                    return null
                }
                val pack = StickerStorage.getPack(context, packId) ?: run {
                    android.util.Log.e(TAG, "query STICKERS: no se encontró paquete $packId")
                    return null
                }
                android.util.Log.d(TAG, "query STICKERS: devolviendo ${pack.stickers.size} stickers para ${pack.name} ($packId)")
                getStickersCursor(pack)
            }
            else -> {
                android.util.Log.e(TAG, "query desconocido: $uri")
                throw IllegalArgumentException("Unknown URI: $uri")
            }
        }
    }

    private fun getMetadataCursor(packs: List<StickerPack>): Cursor {
        val columns = arrayOf(
            STICKER_PACK_IDENTIFIER,
            STICKER_PACK_NAME,
            STICKER_PACK_PUBLISHER,
            STICKER_PACK_ICON,
            ANDROID_APP_DOWNLOAD_LINK,
            IOS_APP_DOWNLOAD_LINK,
            PUBLISHER_EMAIL,
            PUBLISHER_WEBSITE,
            PRIVACY_POLICY_WEBSITE,
            LICENSE_AGREEMENT_WEBSITE,
            IMAGE_DATA_VERSION,
            AVOID_CACHE,
            ANIMATED_STICKER_PACK
        )
        val cursor = MatrixCursor(columns)
        for (pack in packs) {
            cursor.addRow(
                arrayOf(
                    pack.identifier,
                    pack.name,
                    pack.publisher,
                    pack.trayImageFile,
                    "", // Play Store link
                    "", // iOS link
                    pack.publisherEmail,
                    pack.publisherWebsite,
                    pack.privacyPolicyWebsite,
                    pack.licenseAgreementWebsite,
                    pack.imageDataVersion,
                    if (pack.avoidCache) 1 else 0,
                    if (pack.animatedStickerPack) 1 else 0
                )
            )
        }
        return cursor
    }

    private fun getStickersCursor(pack: StickerPack): Cursor {
        val cursor = MatrixCursor(arrayOf(STICKER_FILE_NAME, STICKER_FILE_EMOJI))
        for (sticker in pack.stickers) {
            val emojiString = if (sticker.emojis.isEmpty()) "" else sticker.emojis.take(3).joinToString(",")
            cursor.addRow(arrayOf(sticker.imageFileName, emojiString))
        }
        return cursor
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        android.util.Log.d(TAG, "openFile solicitado: uri=$uri, mode=$mode")
        val file = getTargetFile(uri)
        android.util.Log.d(TAG, "openFile resolvió archivo: ${file.absolutePath}, tamaño=${file.length()} bytes")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Throws(FileNotFoundException::class)
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        android.util.Log.d(TAG, "openAssetFile solicitado: uri=$uri, mode=$mode")
        val file = getTargetFile(uri)
        android.util.Log.d(TAG, "openAssetFile resolvió archivo: ${file.absolutePath}, tamaño=${file.length()} bytes")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun getTargetFile(uri: Uri): File {
        val context = context ?: throw FileNotFoundException("Context is null")
        val match = uriMatcher.match(uri)
        val pathSegments = uri.pathSegments

        return when {
            match == STICKERS_ASSET || pathSegments.size >= 3 -> {
                val packId = pathSegments[pathSegments.size - 2]
                val fileName = pathSegments[pathSegments.size - 1]
                val targetFile = StickerStorage.getStickerFile(context, packId, fileName)
                if (targetFile.exists()) {
                    targetFile
                } else {
                    findFileInAnyPack(context, fileName)
                        ?: throw FileNotFoundException("Sticker file does not exist: ${targetFile.absolutePath}")
                }
            }
            match == STICKER_PACK_TRAY_ICON || pathSegments.size == 2 -> {
                val fileName = uri.lastPathSegment ?: throw FileNotFoundException("No file name in URI: $uri")
                findFileInAnyPack(context, fileName)
                    ?: throw FileNotFoundException("Tray icon does not exist: $fileName")
            }
            else -> throw IllegalArgumentException("Unsupported URI for file access: $uri")
        }
    }

    private fun findFileInAnyPack(context: Context, fileName: String): File? {
        val packs = StickerStorage.getAllPacks(context)
        for (pack in packs) {
            val file = StickerStorage.getStickerFile(context, pack.identifier, fileName)
            if (file.exists()) return file
        }
        val rootDir = File(context.filesDir, "stickers")
        rootDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val candidate = File(dir, fileName)
                if (candidate.exists()) return candidate
            }
        }
        return null
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            METADATA -> "vnd.android.cursor.dir/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.metadata"
            METADATA_PACK -> "vnd.android.cursor.item/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.metadata"
            STICKERS -> "vnd.android.cursor.dir/vnd.${BuildConfig.CONTENT_PROVIDER_AUTHORITY}.stickers"
            STICKERS_ASSET, STICKER_PACK_TRAY_ICON -> "image/webp"
            else -> "image/webp"
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
