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

                val bytes = openStream(context, sourceUri)?.use { it.readBytes() }
                    ?: return false

                // Normalize animated WebP to exactly 512x512 canvas, center frames, and strip EXIF
                val normalizedBytes = normalizeAnimatedWebP(bytes)

                // WhatsApp strict limit for animated stickers is 500 KB (500,000 bytes)
                if (normalizedBytes.size > MAX_ANIMATED_STICKER_BYTES) {
                    return false
                }

                FileOutputStream(destinationFile).use { it.write(normalizedBytes) }
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

    /**
     * Normalizes an animated WebP file to strictly conform to WhatsApp third-party requirements:
     * - Forces canvas to exactly 512x512 pixels in VP8X chunk.
     * - Centers animation frames (ANMF) within the 512x512 canvas.
     * - Strips extraneous metadata chunks (EXIF, XMP, ICCP) that can cause WhatsApp validation errors.
     * - Ensures file size strictly stays <= 500 KB (500,000 bytes) by safely dropping frames if needed.
     */
    fun normalizeAnimatedWebP(bytes: ByteArray): ByteArray {
        if (bytes.size < 30) return bytes

        // Verify RIFF and WEBP
        if (bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte() ||
            bytes[8] != 'W'.code.toByte() || bytes[9] != 'E'.code.toByte() ||
            bytes[10] != 'B'.code.toByte() || bytes[11] != 'P'.code.toByte()
        ) {
            return bytes
        }

        // First chunk must be VP8X
        val chunk1 = String(bytes, 12, 4, Charsets.US_ASCII)
        if (chunk1 != "VP8X") return bytes

        val curWidthMinus1 = (bytes[24].toInt() and 0xFF) or
                ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16)
        val curHeightMinus1 = (bytes[27].toInt() and 0xFF) or
                ((bytes[28].toInt() and 0xFF) shl 8) or
                ((bytes[29].toInt() and 0xFF) shl 16)

        val curWidth = curWidthMinus1 + 1
        val curHeight = curHeightMinus1 + 1

        val offsetX = ((STICKER_SIZE - curWidth) / 2).coerceAtLeast(0)
        val offsetY = ((STICKER_SIZE - curHeight) / 2).coerceAtLeast(0)

        var vp8xData: ByteArray? = null
        var animData: ByteArray? = null
        val anmfChunks = mutableListOf<ByteArray>()

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val fourCC = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = ((bytes[pos + 4].toInt() and 0xFF) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 7].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL

            val dataStart = pos + 8
            val dataEnd = minOf(bytes.size.toLong(), dataStart + chunkSize).toInt()
            val actualLen = (dataEnd - dataStart).coerceAtLeast(0)
            val chunkData = bytes.copyOfRange(dataStart, dataStart + actualLen)

            when (fourCC) {
                "VP8X" -> {
                    // Update canvas dimensions to 512x512: 511 = 0x01FF
                    chunkData[4] = 0xFF.toByte()
                    chunkData[5] = 0x01.toByte()
                    chunkData[6] = 0x00.toByte()
                    chunkData[7] = 0xFF.toByte()
                    chunkData[8] = 0x01.toByte()
                    chunkData[9] = 0x00.toByte()
                    vp8xData = chunkData
                }
                "ANIM" -> {
                    animData = chunkData
                }
                "ANMF" -> {
                    if (chunkData.size >= 16) {
                        val origFrameX = ((chunkData[0].toInt() and 0xFF) or
                                ((chunkData[1].toInt() and 0xFF) shl 8) or
                                ((chunkData[2].toInt() and 0xFF) shl 16)) * 2
                        val origFrameY = ((chunkData[3].toInt() and 0xFF) or
                                ((chunkData[4].toInt() and 0xFF) shl 8) or
                                ((chunkData[5].toInt() and 0xFF) shl 16)) * 2

                        val newFrameX = ((origFrameX + offsetX) / 2).coerceAtLeast(0)
                        val newFrameY = ((origFrameY + offsetY) / 2).coerceAtLeast(0)

                        chunkData[0] = (newFrameX and 0xFF).toByte()
                        chunkData[1] = ((newFrameX shr 8) and 0xFF).toByte()
                        chunkData[2] = ((newFrameX shr 16) and 0xFF).toByte()

                        chunkData[3] = (newFrameY and 0xFF).toByte()
                        chunkData[4] = ((newFrameY shr 8) and 0xFF).toByte()
                        chunkData[5] = ((newFrameY shr 16) and 0xFF).toByte()

                        // WhatsApp Rule: Frame duration must be >= 8 ms! If 0 or < 8, set safe default 100ms
                        var frameDuration = (chunkData[12].toInt() and 0xFF) or
                                ((chunkData[13].toInt() and 0xFF) shl 8) or
                                ((chunkData[14].toInt() and 0xFF) shl 16)
                        if (frameDuration < 8) {
                            frameDuration = 100
                            chunkData[12] = (frameDuration and 0xFF).toByte()
                            chunkData[13] = ((frameDuration shr 8) and 0xFF).toByte()
                            chunkData[14] = ((frameDuration shr 16) and 0xFF).toByte()
                        }
                    }
                    anmfChunks.add(chunkData)
                }
                // Skip EXIF, XMP, ICCP to prevent WhatsApp corruption errors
            }

            val paddedSize = if (chunkSize % 2L != 0L) chunkSize + 1 else chunkSize
            pos += (8 + paddedSize).toInt()
        }

        if (vp8xData == null || anmfChunks.isEmpty()) {
            return bytes
        }

        // WhatsApp animated stickers requirement: at least 2 frames for animation loop
        if (anmfChunks.size == 1) {
            val f0 = anmfChunks[0]
            val f0Dur = 500
            f0[12] = (f0Dur and 0xFF).toByte()
            f0[13] = ((f0Dur shr 8) and 0xFF).toByte()
            f0[14] = ((f0Dur shr 16) and 0xFF).toByte()
            anmfChunks.add(f0.copyOf())
        }

        // WhatsApp Rule: Total animation duration must be <= 10,000 ms (10 seconds)
        var totalDur = 0
        for (c in anmfChunks) {
            if (c.size >= 16) {
                val d = (c[12].toInt() and 0xFF) or
                        ((c[13].toInt() and 0xFF) shl 8) or
                        ((c[14].toInt() and 0xFF) shl 16)
                totalDur += d
            }
        }
        if (totalDur > 10000 && totalDur > 0) {
            val scaleFactor = 9500.0 / totalDur
            for (c in anmfChunks) {
                if (c.size >= 16) {
                    val d = (c[12].toInt() and 0xFF) or
                            ((c[13].toInt() and 0xFF) shl 8) or
                            ((c[14].toInt() and 0xFF) shl 16)
                    val newD = (d * scaleFactor).toInt().coerceIn(8, 10000)
                    c[12] = (newD and 0xFF).toByte()
                    c[13] = ((newD shr 8) and 0xFF).toByte()
                    c[14] = ((newD shr 16) and 0xFF).toByte()
                }
            }
        }

        // WhatsApp hard limit is 500 KB (500,000 bytes). If over limit, drop trailing frames safely
        val maxTargetBytes = 500_000
        fun calculateTotalSize(anmfs: List<ByteArray>): Int {
            var s = 12 // RIFF (4) + size (4) + WEBP (4)
            s += 8 + vp8xData.size + (vp8xData.size % 2)
            if (animData != null) s += 8 + animData.size + (animData.size % 2)
            for (c in anmfs) {
                s += 8 + c.size + (c.size % 2)
            }
            return s
        }

        while (anmfChunks.size > 3 && calculateTotalSize(anmfChunks) > maxTargetBytes) {
            anmfChunks.removeAt(anmfChunks.size - 1)
        }

        val bos = ByteArrayOutputStream(calculateTotalSize(anmfChunks) + 128)
        bos.write("RIFF".toByteArray(Charsets.US_ASCII))
        // Placeholder for file size
        bos.write(0); bos.write(0); bos.write(0); bos.write(0)
        bos.write("WEBP".toByteArray(Charsets.US_ASCII))

        writeRawChunk(bos, "VP8X", vp8xData)
        if (animData != null) {
            writeRawChunk(bos, "ANIM", animData)
        }
        for (c in anmfChunks) {
            writeRawChunk(bos, "ANMF", c)
        }

        val result = bos.toByteArray()
        val riffSize = result.size - 8
        result[4] = (riffSize and 0xFF).toByte()
        result[5] = ((riffSize shr 8) and 0xFF).toByte()
        result[6] = ((riffSize shr 16) and 0xFF).toByte()
        result[7] = ((riffSize shr 24) and 0xFF).toByte()

        return result
    }

    private fun writeRawChunk(bos: ByteArrayOutputStream, fourCC: String, data: ByteArray) {
        bos.write(fourCC.toByteArray(Charsets.US_ASCII))
        val len = data.size
        bos.write(len and 0xFF)
        bos.write((len shr 8) and 0xFF)
        bos.write((len shr 16) and 0xFF)
        bos.write((len shr 24) and 0xFF)
        bos.write(data)
        if (len % 2 != 0) {
            bos.write(0)
        }
    }
}
