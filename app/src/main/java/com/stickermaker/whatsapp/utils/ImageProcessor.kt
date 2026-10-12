package com.stickermaker.whatsapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.min

data class StickerInspectionResult(
    val isValidWebP: Boolean,
    val isAnimated: Boolean,
    val payloadHash: String,
    val visualDHash: String,
    val checksum: String = payloadHash
)

object ImageProcessor {

    private const val STICKER_SIZE = 512
    private const val STICKER_SAFE_MARGIN = 16
    private const val MAX_STATIC_STICKER_BYTES = 100 * 1024 // 100 KB limit for WhatsApp static stickers
    private const val MAX_ANIMATED_STICKER_BYTES = 500 * 1024 // 500 KB limit for WhatsApp animated stickers

    private const val TRAY_SIZE = 96
    private const val MAX_TRAY_BYTES = 50 * 1024 // 50 KB limit for tray icon

    /**
     * Opens an InputStream supporting both content:// and direct file:// URIs.
     */
    fun openStream(context: Context, uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) FileInputStream(File(path)) else null
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks if a Uri points strictly to a WebP file by reading its magic bytes (RIFF....WEBP).
     */
    fun isStrictlyWebP(context: Context, sourceUri: Uri): Boolean {
        return try {
            openStream(context, sourceUri)?.use { stream ->
                val header = ByteArray(12)
                val bytesRead = stream.read(header)
                if (bytesRead >= 12) {
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() &&
                    header[9] == 'E'.code.toByte() &&
                    header[10] == 'B'.code.toByte() &&
                    header[11] == 'P'.code.toByte()
                } else {
                    false
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if a Uri points to an animated WebP file by checking for the animation flag in VP8X
     * or the presence of the ANIM chunk in the header.
     */
    fun isAnimatedWebP(context: Context, sourceUri: Uri): Boolean {
        return try {
            openStream(context, sourceUri)?.use { stream ->
                val header = ByteArray(4096)
                val bytesRead = stream.read(header)
                if (bytesRead >= 21) {
                    val isRiff = header[0] == 'R'.code.toByte() &&
                                 header[1] == 'I'.code.toByte() &&
                                 header[2] == 'F'.code.toByte() &&
                                 header[3] == 'F'.code.toByte()
                    val isWebp = header[8] == 'W'.code.toByte() &&
                                 header[9] == 'E'.code.toByte() &&
                                 header[10] == 'B'.code.toByte() &&
                                 header[11] == 'P'.code.toByte()
                    if (!isRiff || !isWebp) return false

                    // VP8X chunk header at offset 12..15
                    val isVp8x = header[12] == 'V'.code.toByte() &&
                                 header[13] == 'P'.code.toByte() &&
                                 header[14] == '8'.code.toByte() &&
                                 header[15] == 'X'.code.toByte()
                    // Flag byte at offset 20: bit 1 is Animation flag (0x02)
                    if (isVp8x && (header[20].toInt() and 0x02) != 0) {
                        return true
                    }

                    // Also search for "ANIM" FourCC anywhere in the header buffer
                    for (i in 12 until bytesRead - 3) {
                        if (header[i] == 'A'.code.toByte() &&
                            header[i + 1] == 'N'.code.toByte() &&
                            header[i + 2] == 'I'.code.toByte() &&
                            header[i + 3] == 'M'.code.toByte()) {
                            return true
                        }
                    }
                }
                false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Inspects a WebP file:
     * - Validates RIFF...WEBP container
     * - Detects if it's animated or static
     * - Computes normalized image payload hash (strictly ignoring EXIF, XMP, and ICCP metadata chunks)
     * - Computes perceptual difference hash (dHash) on frame 0 to catch visual duplicates even if re-encoded
     */
    fun inspectWebP(context: Context, sourceUri: Uri): StickerInspectionResult {
        return try {
            val bytes = openStream(context, sourceUri)?.use { it.readBytes() }
                ?: return StickerInspectionResult(isValidWebP = false, isAnimated = false, payloadHash = "", visualDHash = "")

            if (bytes.size < 12) {
                return StickerInspectionResult(isValidWebP = false, isAnimated = false, payloadHash = "", visualDHash = "")
            }

            val isRiff = bytes[0] == 'R'.code.toByte() &&
                         bytes[1] == 'I'.code.toByte() &&
                         bytes[2] == 'F'.code.toByte() &&
                         bytes[3] == 'F'.code.toByte()
            val isWebp = bytes[8] == 'W'.code.toByte() &&
                         bytes[9] == 'E'.code.toByte() &&
                         bytes[10] == 'B'.code.toByte() &&
                         bytes[11] == 'P'.code.toByte()

            if (!isRiff || !isWebp) {
                return StickerInspectionResult(isValidWebP = false, isAnimated = false, payloadHash = "", visualDHash = "")
            }

            var isAnim = false
            val digest = MessageDigest.getInstance("MD5")
            var offset = 12
            var imageChunksFound = 0

            // Walk RIFF chunks
            while (offset + 8 <= bytes.size) {
                val fourCC = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = ((bytes[offset + 4].toInt() and 0xFF) or
                        ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                        ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                        ((bytes[offset + 7].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL

                val chunkDataStart = offset + 8
                val chunkDataEnd = minOf(bytes.size.toLong(), chunkDataStart + chunkSize).toInt()
                val actualChunkLen = (chunkDataEnd - chunkDataStart).coerceAtLeast(0)

                when (fourCC) {
                    "VP8X" -> {
                        imageChunksFound++
                        digest.update(bytes, offset, 4) // FourCC
                        if (actualChunkLen > 0) {
                            digest.update(bytes, chunkDataStart, actualChunkLen)
                            // Animation flag is at offset 0 of chunk data (byte 20 of file)
                            if (actualChunkLen >= 1 && (bytes[chunkDataStart].toInt() and 0x02) != 0) {
                                isAnim = true
                            }
                        }
                    }
                    "ANIM" -> {
                        imageChunksFound++
                        isAnim = true
                        digest.update(bytes, offset, 4)
                        if (actualChunkLen > 0) digest.update(bytes, chunkDataStart, actualChunkLen)
                    }
                    "ANMF", "VP8 ", "VP8L", "ALPH" -> {
                        imageChunksFound++
                        digest.update(bytes, offset, 4)
                        if (actualChunkLen > 0) digest.update(bytes, chunkDataStart, actualChunkLen)
                    }
                    "EXIF", "XMP ", "ICCP" -> {
                        // Skip metadata chunk completely from digest!
                        // This prevents different EXIF / author / pack IDs from causing false uniqueness.
                    }
                    else -> {
                        // Include unknown chunks in digest for safety
                        digest.update(bytes, offset, 4)
                        if (actualChunkLen > 0) digest.update(bytes, chunkDataStart, actualChunkLen)
                    }
                }

                // Chunks are padded to even 2-byte boundary
                val padding = if (chunkSize % 2L != 0L) 1 else 0
                val nextOffset = (chunkDataStart + chunkSize + padding).toInt()
                if (nextOffset <= offset) break // Safety against infinite loop
                offset = nextOffset
            }

            // Fallback if no specific chunks recognized: hash entire file
            val payloadHash = if (imageChunksFound > 0) {
                digest.digest().joinToString("") { "%02x".format(it) }
            } else {
                val fullDigest = MessageDigest.getInstance("MD5")
                fullDigest.update(bytes)
                fullDigest.digest().joinToString("") { "%02x".format(it) }
            }

            // Perceptual visual hash (dHash) on frame 0
            val visualDHash = computeDHash(context, sourceUri)

            StickerInspectionResult(
                isValidWebP = true,
                isAnimated = isAnim,
                payloadHash = payloadHash,
                visualDHash = visualDHash
            )
        } catch (e: Exception) {
            StickerInspectionResult(isValidWebP = false, isAnimated = false, payloadHash = "", visualDHash = "")
        }
    }

    /**
     * Computes a 64-bit difference hash (dHash) of frame 0.
     * Extremely fast and resilient against re-encoding, EXIF modifications, and compression differences.
     */
    fun computeDHash(context: Context, sourceUri: Uri): String {
        return try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 8
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val original = openStream(context, sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return ""

            val scaled = Bitmap.createScaledBitmap(original, 9, 8, true)
            if (scaled != original) {
                original.recycle()
            }

            var hash = 0L
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val p1 = scaled.getPixel(x, y)
                    val p2 = scaled.getPixel(x + 1, y)
                    val lum1 = (p1 and 0xFF) + ((p1 shr 8) and 0xFF) + ((p1 shr 16) and 0xFF)
                    val lum2 = (p2 and 0xFF) + ((p2 shr 8) and 0xFF) + ((p2 shr 16) and 0xFF)
                    if (lum1 > lum2) {
                        hash = hash or (1L shl (y * 8 + x))
                    }
                }
            }
            scaled.recycle()
            "%016x".format(hash)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Strictly processes a WebP sticker image.
     * If isAnimated is true: preserves raw animated WebP data (all frames & timing) under 500KB.
     * If isAnimated is false: centers and adjusts static image to 512x512 WebP under 100KB.
     */
    fun processStickerImage(
        context: Context,
        sourceUri: Uri,
        destinationFile: File,
        isAnimated: Boolean = false
    ): Boolean {
        // Enforce strict WebP format check
        if (!isStrictlyWebP(context, sourceUri)) {
            return false
        }

        return try {
            if (isAnimated) {
                // Must be an animated WebP
                if (!isAnimatedWebP(context, sourceUri)) {
                    return false
                }

                // Copy raw bytes to retain all animation frames and timing perfectly
                val bytes = openStream(context, sourceUri)?.use { it.readBytes() }
                    ?: return false

                // WhatsApp strict limit for animated stickers is 500 KB
                if (bytes.size > MAX_ANIMATED_STICKER_BYTES) {
                    return false
                }

                FileOutputStream(destinationFile).use { it.write(bytes) }
                true
            } else {
                // Check original dimensions and size
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                openStream(context, sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }

                val isExactSquare512 = options.outWidth == STICKER_SIZE && options.outHeight == STICKER_SIZE

                // Decode Bitmap
                val inputStream: InputStream? = openStream(context, sourceUri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap == null) return false

                val scaledBitmap = fitAndCenterOnCanvas(
                    originalBitmap,
                    targetSize = STICKER_SIZE,
                    margin = if (isExactSquare512) 0 else STICKER_SAFE_MARGIN
                )

                val compressedBytes = compressToWebP(scaledBitmap, MAX_STATIC_STICKER_BYTES)
                scaledBitmap.recycle()
                originalBitmap.recycle()

                if (compressedBytes != null) {
                    FileOutputStream(destinationFile).use { it.write(compressedBytes) }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Generates a 96x96 tray icon in WebP format (<50KB).
     * Works for both static and animated WebP (BitmapFactory decodes frame 0 of animated WebP).
     */
    fun generateTrayIcon(context: Context, sourceUri: Uri, destinationFile: File): Boolean {
        return try {
            val inputStream: InputStream? = openStream(context, sourceUri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return false

            val scaledBitmap = fitAndCenterOnCanvas(
                originalBitmap,
                targetSize = TRAY_SIZE,
                margin = 4
            )

            val compressedBytes = compressToWebP(scaledBitmap, MAX_TRAY_BYTES)
            scaledBitmap.recycle()
            originalBitmap.recycle()

            if (compressedBytes != null) {
                FileOutputStream(destinationFile).use { it.write(compressedBytes) }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun fitAndCenterOnCanvas(source: Bitmap, targetSize: Int, margin: Int): Bitmap {
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val availableSize = targetSize - (margin * 2)
        val srcWidth = source.width.toFloat()
        val srcHeight = source.height.toFloat()

        val scale = min(availableSize / srcWidth, availableSize / srcHeight)
        val destWidth = srcWidth * scale
        val destHeight = srcHeight * scale

        val left = margin + (availableSize - destWidth) / 2f
        val top = margin + (availableSize - destHeight) / 2f
        val destRect = RectF(left, top, left + destWidth, top + destHeight)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, destRect, paint)

        return output
    }

    private fun compressToWebP(bitmap: Bitmap, maxByteLimit: Int): ByteArray? {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

        var quality = 90
        var bytes: ByteArray

        do {
            val stream = ByteArrayOutputStream()
            bitmap.compress(format, quality, stream)
            bytes = stream.toByteArray()
            quality -= 10
        } while (bytes.size > maxByteLimit && quality >= 20)

        return if (bytes.size <= maxByteLimit) bytes else null
    }
}
