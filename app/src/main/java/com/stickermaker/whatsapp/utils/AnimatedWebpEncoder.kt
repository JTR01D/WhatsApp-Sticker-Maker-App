package com.stickermaker.whatsapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Converts a trimmed video segment into a WhatsApp-compliant 512x512 Animated WebP (<= 500 KB)
 * using Android's native MediaMetadataRetriever + WebP frame encoding + RIFF/WebP muxing.
 */
object AnimatedWebpEncoder {

    private const val STICKER_SIZE = 512
    private const val MAX_OUTPUT_BYTES = 490 * 1024 // Stay safely below WhatsApp's 500 KB cap
    private const val MAX_FRAMES = 90

    data class EncodeResult(
        val success: Boolean,
        val fileSizeBytes: Long = 0L,
        val frameCount: Int = 0,
        val errorMessage: String? = null
    )

    fun encodeVideoToAnimatedWebp(
        context: Context,
        videoUri: Uri,
        startMs: Long,
        endMs: Long,
        isCropFill: Boolean,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): EncodeResult {
        val durationMs = (endMs - startMs).coerceAtLeast(300L)
        val targetFps = 15f
        val desiredFrames = ((durationMs / 1000f) * targetFps).roundToInt().coerceIn(6, MAX_FRAMES)
        val stepMs = durationMs.toDouble() / desiredFrames.toDouble()

        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Bitmap>()

        try {
            retriever.setDataSource(context, videoUri)
            for (i in 0 until desiredFrames) {
                val timeUs = ((startMs + (i * stepMs)).toLong()) * 1000L
                val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        STICKER_SIZE * 2,
                        STICKER_SIZE * 2
                    ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                if (rawBitmap != null) {
                    val formatted = formatSquareFrame(rawBitmap, isCropFill)
                    if (rawBitmap !== formatted) {
                        rawBitmap.recycle()
                    }
                    frames.add(formatted)
                }
                val pct = ((i + 1) * 60) / desiredFrames
                onProgress(pct.coerceIn(1, 60))
            }
        } catch (e: Exception) {
            frames.forEach { it.recycle() }
            return EncodeResult(false, errorMessage = e.localizedMessage ?: "Error extracting frames")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }

        if (frames.isEmpty()) {
            return EncodeResult(false, errorMessage = "No frames could be extracted from video")
        }

        try {
            // Prioritize keeping FULL FPS fluid motion by reducing quality first,
            // and only drop frames as a last resort if still over 490 KB.
            val halfFrames = downsampleFrames(frames)
            val attempts = listOf(
                Pair(frames, 52),
                Pair(frames, 36),
                Pair(frames, 24),
                Pair(frames, 16),
                Pair(halfFrames, 24),
                Pair(halfFrames, 14)
            )

            var bestBytes: ByteArray? = null
            var bestFrameCount = frames.size

            for ((idx, attempt) in attempts.withIndex()) {
                val subset = attempt.first
                if (subset.isEmpty()) continue
                val quality = attempt.second
                val frameDurationMs = (durationMs / subset.size).toInt().coerceIn(40, 2500)

                val encodedFrames = subset.map { bmp -> encodeSingleWebpFrame(bmp, quality) }
                val muxed = muxAnimatedWebp(encodedFrames, frameDurationMs)

                bestBytes = muxed
                bestFrameCount = subset.size
                onProgress((65 + idx * 7).coerceAtMost(95))

                if (muxed.size <= MAX_OUTPUT_BYTES) {
                    break
                }
            }

            val finalBytes = bestBytes ?: return EncodeResult(false, errorMessage = "Failed to mux WebP")
            if (finalBytes.size > 500 * 1024) {
                return EncodeResult(
                    false,
                    fileSizeBytes = finalBytes.size.toLong(),
                    errorMessage = "El clip es demasiado complejo para caber en 500 KB. Prueba un recorte más corto."
                )
            }

            FileOutputStream(outputFile).use { fos ->
                fos.write(finalBytes)
            }
            onProgress(100)
            return EncodeResult(
                success = true,
                fileSizeBytes = finalBytes.size.toLong(),
                frameCount = bestFrameCount
            )
        } catch (e: Exception) {
            return EncodeResult(false, errorMessage = e.localizedMessage ?: "Error encoding WebP")
        } finally {
            frames.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun downsampleFrames(source: List<Bitmap>): List<Bitmap> {
        if (source.size <= 3) return source
        return source.filterIndexed { index, _ -> index % 2 == 0 }
    }

    private fun formatSquareFrame(source: Bitmap, isCropFill: Boolean): Bitmap {
        val out = Bitmap.createBitmap(STICKER_SIZE, STICKER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.TRANSPARENT)

        val scale = if (isCropFill) {
            max(STICKER_SIZE.toFloat() / source.width, STICKER_SIZE.toFloat() / source.height)
        } else {
            min(STICKER_SIZE.toFloat() / source.width, STICKER_SIZE.toFloat() / source.height)
        }

        val scaledW = source.width * scale
        val scaledH = source.height * scale
        val left = (STICKER_SIZE - scaledW) / 2f
        val top = (STICKER_SIZE - scaledH) / 2f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, RectF(left, top, left + scaledW, top + scaledH), paint)
        return out
    }

    private fun encodeSingleWebpFrame(bitmap: Bitmap, quality: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(format, quality, baos)
        return baos.toByteArray()
    }

    private fun muxAnimatedWebp(singleFrameWebps: List<ByteArray>, frameDurationMs: Int): ByteArray {
        var hasAlpha = false
        val frameChunksList = mutableListOf<ByteArray>()

        for (webp in singleFrameWebps) {
            val extracted = extractFrameBitstreamChunks(webp)
            if (extracted.second) hasAlpha = true
            frameChunksList.add(extracted.first)
        }

        val body = ByteArrayOutputStream()

        // 1. VP8X chunk (10 bytes payload)
        val vp8xPayload = ByteArray(10)
        vp8xPayload[0] = (0x02 or (if (hasAlpha) 0x10 else 0x00)).toByte() // Animation (+ Alpha if needed)
        writeLe24(vp8xPayload, 4, STICKER_SIZE - 1)
        writeLe24(vp8xPayload, 7, STICKER_SIZE - 1)
        writeChunk(body, "VP8X", vp8xPayload)

        // 2. ANIM chunk (6 bytes payload: 4B bg color + 2B loop count 0 = infinite)
        val animPayload = ByteArray(6)
        writeChunk(body, "ANIM", animPayload)

        // 3. ANMF chunks
        for (frameChunks in frameChunksList) {
            val anmfOut = ByteArrayOutputStream()
            val header = ByteArray(16)
            // x/2 = 0 (bytes 0..2), y/2 = 0 (bytes 3..5)
            writeLe24(header, 6, STICKER_SIZE - 1)
            writeLe24(header, 9, STICKER_SIZE - 1)
            writeLe24(header, 12, frameDurationMs)
            header[15] = 0x02.toByte() // Do not dispose (0) + no alpha blending (1) avoids inter-frame flicker
            anmfOut.write(header)
            anmfOut.write(frameChunks)
            writeChunk(body, "ANMF", anmfOut.toByteArray())
        }

        val bodyBytes = body.toByteArray()
        val riff = ByteArrayOutputStream(12 + bodyBytes.size)
        riff.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(riff, 4 + bodyBytes.size)
        riff.write("WEBP".toByteArray(Charsets.US_ASCII))
        riff.write(bodyBytes)
        return riff.toByteArray()
    }

    private fun extractFrameBitstreamChunks(webp: ByteArray): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream()
        var hasAlpha = false
        var pos = 12 // Skip RIFF + size + WEBP

        while (pos + 8 <= webp.size) {
            val fourCc = String(webp, pos, 4, Charsets.US_ASCII)
            val chunkSize = (webp[pos + 4].toInt() and 0xFF) or
                ((webp[pos + 5].toInt() and 0xFF) shl 8) or
                ((webp[pos + 6].toInt() and 0xFF) shl 16) or
                ((webp[pos + 7].toInt() and 0xFF) shl 24)
            pos += 8
            if (pos + chunkSize > webp.size) break

            if (fourCc == "ALPH" || fourCc == "VP8 " || fourCc == "VP8L") {
                if (fourCc == "ALPH") hasAlpha = true
                writeChunk(out, fourCc, webp.copyOfRange(pos, pos + chunkSize))
            }

            pos += chunkSize + (chunkSize and 1)
        }
        return Pair(out.toByteArray(), hasAlpha)
    }

    private fun writeChunk(out: ByteArrayOutputStream, fourCc: String, payload: ByteArray) {
        out.write(fourCc.toByteArray(Charsets.US_ASCII))
        writeLe32(out, payload.size)
        out.write(payload)
        if ((payload.size and 1) != 0) {
            out.write(0)
        }
    }

    private fun writeLe24(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
    }

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }
}
