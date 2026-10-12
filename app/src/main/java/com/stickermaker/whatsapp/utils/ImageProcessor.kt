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
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.min

object ImageProcessor {

    private const val STICKER_SIZE = 512
    private const val STICKER_SAFE_MARGIN = 16
    private const val MAX_STICKER_BYTES = 100 * 1024 // 100 KB limit for WhatsApp

    private const val TRAY_SIZE = 96
    private const val MAX_TRAY_BYTES = 50 * 1024 // 50 KB limit for tray icon

    /**
     * Checks if a Uri points strictly to a WebP file by reading its magic bytes (RIFF....WEBP).
     */
    fun isStrictlyWebP(context: Context, sourceUri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
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
     * Strictly processes a WebP image. Excludes any other format.
     * If already 512x512 and <100KB, copies it directly.
     * Otherwise, centers and adjusts to 512x512 WebP (<100KB).
     */
    fun processStickerImage(context: Context, sourceUri: Uri, destinationFile: File): Boolean {
        // Enforce strict WebP format check
        if (!isStrictlyWebP(context, sourceUri)) {
            return false
        }

        return try {
            // Check original dimensions and size
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            val isExactSquare512 = options.outWidth == STICKER_SIZE && options.outHeight == STICKER_SIZE

            // Decode Bitmap (this automatically strips any foreign EXIF metadata like Sticker.ly chunks)
            val inputStream: InputStream? = context.contentResolver.openInputStream(sourceUri)
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (originalBitmap == null) return false

            val scaledBitmap = fitAndCenterOnCanvas(
                originalBitmap,
                targetSize = STICKER_SIZE,
                margin = if (isExactSquare512) 0 else STICKER_SAFE_MARGIN
            )

            val compressedBytes = compressToWebP(scaledBitmap, MAX_STICKER_BYTES)
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

    /**
     * Generates a 96x96 tray icon in WebP format (<50KB).
     */
    fun generateTrayIcon(context: Context, sourceUri: Uri, destinationFile: File): Boolean {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(sourceUri)
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
