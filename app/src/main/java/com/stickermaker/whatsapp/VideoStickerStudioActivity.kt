package com.stickermaker.whatsapp

import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.AspectRatioFrameLayout
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.stickermaker.whatsapp.databinding.ActivityVideoStickerStudioBinding
import com.stickermaker.whatsapp.databinding.DialogCreatePackBinding
import com.stickermaker.whatsapp.model.StickerPack
import com.stickermaker.whatsapp.utils.AnimatedWebpEncoder
import com.stickermaker.whatsapp.utils.StickerStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.min

@OptIn(UnstableApi::class)
class VideoStickerStudioActivity : AppCompatActivity() {

    companion object {
        private const val MIN_STICKER_DURATION_SEC = 0.5f
    }

    private lateinit var binding: ActivityVideoStickerStudioBinding
    private var exoPlayer: ExoPlayer? = null
    private var audioPlayer: ExoPlayer? = null
    private var activeTransformer: Transformer? = null

    private var selectedVideoUri: Uri? = null
    private var selectedImageUri: Uri? = null
    private var selectedExternalAudioUri: Uri? = null
    private var isImageSource: Boolean = false

    private var maxStickerDurationSec: Float = 10.0f
    private var mediaDurationSec: Float = 10.0f
    private var trimStartSec: Float = 0.0f
    private var trimEndSec: Float = 10.0f
    private var hasOriginalAudioTrack: Boolean = true
    private var isCropFill: Boolean = true
    private var isUpdatingSlider: Boolean = false
    private var isPreviewPlaying: Boolean = true

    private var exportedCacheFile: File? = null
    private var webpExportJob: Job? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private val progressRunnable = object : Runnable {
        override fun run() {
            val transformer = activeTransformer ?: return
            val state = transformer.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                val pct = progressHolder.progress.coerceIn(0, 100)
                binding.progressExport.progress = pct
                binding.tvExportPercent.text = "$pct%"
            }
            progressHandler.postDelayed(this, 120L)
        }
    }

    private val pickVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                takePersistable(uri)
                loadVideo(uri)
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                takePersistable(uri)
                loadImageSource(uri)
            }
        }

    private val pickExternalAudioLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                takePersistable(uri)
                loadExternalAudio(uri)
            }
        }

    private fun takePersistable(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoStickerStudioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUi()
    }

    private fun setupUi() {
        binding.btnBackStudio.setOnClickListener { finish() }

        binding.btnSelectVideo.setOnClickListener { pickVideoLauncher.launch(arrayOf("video/*")) }
        binding.btnSelectImageForAudio.setOnClickListener { pickImageLauncher.launch(arrayOf("image/*")) }
        binding.btnChangeVideo.setOnClickListener {
            if (isImageSource) {
                pickImageLauncher.launch(arrayOf("image/*"))
            } else {
                pickVideoLauncher.launch(arrayOf("video/*"))
            }
        }

        binding.btnPickExternalAudio.setOnClickListener {
            pickExternalAudioLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnClearExternalAudio.setOnClickListener {
            if (isImageSource) {
                Toast.makeText(this, R.string.need_audio_for_image, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedExternalAudioUri = null
            audioPlayer?.pause()
            binding.btnClearExternalAudio.visibility = View.GONE
            if (hasOriginalAudioTrack) {
                binding.switchIncludeAudio.isEnabled = true
                binding.switchIncludeAudio.isChecked = true
                binding.tvAudioStatusDesc.text = getString(R.string.include_audio_desc)
            } else {
                binding.switchIncludeAudio.isChecked = false
                binding.switchIncludeAudio.isEnabled = false
                binding.tvAudioStatusDesc.text = getString(R.string.no_audio_track_detected)
            }
            applyClippedPreview()
        }

        binding.previewClickTarget.setOnClickListener {
            if (isPreviewPlaying) {
                exoPlayer?.pause()
                audioPlayer?.pause()
                isPreviewPlaying = false
                binding.layoutPlayOverlay.visibility = View.VISIBLE
            } else {
                if (!isImageSource) exoPlayer?.play()
                if (selectedExternalAudioUri != null) audioPlayer?.play()
                isPreviewPlaying = true
                binding.layoutPlayOverlay.visibility = View.GONE
            }
        }

        binding.toggleCropMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isCropFill = (checkedId == R.id.btnCropFill)
            binding.playerView.resizeMode = if (isCropFill) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            binding.ivImagePreview.scaleType = if (isCropFill) {
                ImageView.ScaleType.CENTER_CROP
            } else {
                ImageView.ScaleType.FIT_CENTER
            }
        }

        binding.toggleMaxDuration.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            maxStickerDurationSec = if (checkedId == R.id.btnDuration5s) 5.0f else 10.0f
            val maxAllowed = min(mediaDurationSec, maxStickerDurationSec)
            if (trimEndSec - trimStartSec > maxAllowed) {
                isUpdatingSlider = true
                trimEndSec = (trimStartSec + maxAllowed).coerceAtMost(mediaDurationSec)
                binding.rangeSliderTrim.setValues(trimStartSec, trimEndSec)
                isUpdatingSlider = false
                applyClippedPreview()
            }
            updateTrimLabels()
        }

        binding.switchIncludeAudio.setOnCheckedChangeListener { _, isChecked ->
            val audioActive = isChecked && hasAnyAudio()
            if (selectedExternalAudioUri != null) {
                exoPlayer?.volume = 0f
                audioPlayer?.volume = if (audioActive) 1f else 0f
            } else {
                exoPlayer?.volume = if (audioActive) 1f else 0f
            }
            updateAudioBadge(audioActive)
        }

        binding.rangeSliderTrim.addOnChangeListener { slider, _, fromUser ->
            if (!fromUser || isUpdatingSlider) return@addOnChangeListener
            val values = slider.values
            if (values.size < 2) return@addOnChangeListener

            var newStart = values[0]
            var newEnd = values[1]
            val maxAllowed = min(mediaDurationSec, maxStickerDurationSec)

            if (newEnd - newStart > maxAllowed) {
                isUpdatingSlider = true
                if (abs(newStart - trimStartSec) > 0.01f) {
                    newEnd = (newStart + maxAllowed).coerceAtMost(slider.valueTo)
                } else {
                    newStart = (newEnd - maxAllowed).coerceAtLeast(slider.valueFrom)
                }
                slider.setValues(newStart, newEnd)
                isUpdatingSlider = false
            } else if (newEnd - newStart < MIN_STICKER_DURATION_SEC && mediaDurationSec >= MIN_STICKER_DURATION_SEC) {
                isUpdatingSlider = true
                if (abs(newStart - trimStartSec) > 0.01f) {
                    newStart = (newEnd - MIN_STICKER_DURATION_SEC).coerceAtLeast(slider.valueFrom)
                } else {
                    newEnd = (newStart + MIN_STICKER_DURATION_SEC).coerceAtMost(slider.valueTo)
                }
                slider.setValues(newStart, newEnd)
                isUpdatingSlider = false
            }

            trimStartSec = newStart
            trimEndSec = newEnd
            updateTrimLabels()
        }

        binding.rangeSliderTrim.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {}
            override fun onStopTrackingTouch(slider: RangeSlider) {
                applyClippedPreview()
            }
        })

        binding.btnSaveWebpToPack.setOnClickListener {
            showChoosePackAndSaveWebp()
        }

        binding.btnExportClip.setOnClickListener {
            exportClipForWhatsApp()
        }

        binding.btnCancelExport.setOnClickListener {
            cancelExport()
        }
    }

    private fun hasAnyAudio(): Boolean = hasOriginalAudioTrack || selectedExternalAudioUri != null

    private fun loadImageSource(uri: Uri) {
        isImageSource = true
        selectedImageUri = uri
        selectedVideoUri = null
        hasOriginalAudioTrack = false
        exoPlayer?.pause()

        binding.layoutEmptyVideo.visibility = View.GONE
        binding.layoutEditor.visibility = View.VISIBLE
        binding.layoutBottomExport.visibility = View.VISIBLE
        binding.btnChangeVideo.visibility = View.VISIBLE
        binding.btnSaveWebpToPack.visibility = View.GONE

        binding.playerView.visibility = View.GONE
        binding.ivImagePreview.visibility = View.VISIBLE
        Glide.with(this).load(uri).into(binding.ivImagePreview)

        if (selectedExternalAudioUri == null) {
            mediaDurationSec = maxStickerDurationSec
            trimStartSec = 0f
            trimEndSec = min(5.0f, maxStickerDurationSec)
            configureSliderRange()
            binding.switchIncludeAudio.isChecked = false
            binding.switchIncludeAudio.isEnabled = false
            binding.tvAudioStatusDesc.text = getString(R.string.need_audio_for_image)
            updateAudioBadge(false)
            updateTrimLabels()
            // Prompt audio picker immediately for convenience
            pickExternalAudioLauncher.launch(arrayOf("audio/*"))
        } else {
            applyClippedPreview()
        }
    }

    private fun loadVideo(uri: Uri) {
        isImageSource = false
        selectedVideoUri = uri
        selectedImageUri = null
        selectedExternalAudioUri = null
        audioPlayer?.pause()
        binding.btnClearExternalAudio.visibility = View.GONE

        lifecycleScope.launch {
            val (durationMs, audioDetected) = withContext(Dispatchers.IO) {
                val dur = readDurationMs(uri)
                val hasAudio = if (dur > 0L) detectAudioTrack(uri) else false
                Pair(dur, hasAudio)
            }

            if (durationMs < 0L) {
                Toast.makeText(this@VideoStickerStudioActivity, "No se pudo leer el video seleccionado", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (durationMs == 0L) {
                Toast.makeText(this@VideoStickerStudioActivity, "Video inválido o vacío", Toast.LENGTH_SHORT).show()
                return@launch
            }

            hasOriginalAudioTrack = audioDetected
            mediaDurationSec = (durationMs / 1000f).coerceAtLeast(MIN_STICKER_DURATION_SEC)
            trimStartSec = 0f
            trimEndSec = min(mediaDurationSec, maxStickerDurationSec)

            binding.layoutEmptyVideo.visibility = View.GONE
            binding.layoutEditor.visibility = View.VISIBLE
            binding.layoutBottomExport.visibility = View.VISIBLE
            binding.btnChangeVideo.visibility = View.VISIBLE
            binding.btnSaveWebpToPack.visibility = View.VISIBLE

            binding.ivImagePreview.visibility = View.GONE
            binding.playerView.visibility = View.VISIBLE

            configureSliderRange()

            if (hasOriginalAudioTrack) {
                binding.switchIncludeAudio.isEnabled = true
                binding.switchIncludeAudio.isChecked = true
                binding.tvAudioStatusDesc.text = getString(R.string.include_audio_desc)
            } else {
                binding.switchIncludeAudio.isChecked = false
                binding.switchIncludeAudio.isEnabled = false
                binding.tvAudioStatusDesc.text = getString(R.string.no_audio_track_detected)
            }

            updateAudioBadge(hasOriginalAudioTrack && binding.switchIncludeAudio.isChecked)
            updateTrimLabels()
            initPlayers()
            applyClippedPreview()
        }
    }

    private fun loadExternalAudio(uri: Uri) {
        lifecycleScope.launch {
            val (durationMs, displayName) = withContext(Dispatchers.IO) {
                Pair(readDurationMs(uri), queryDisplayName(uri))
            }
            if (durationMs <= 0L) {
                Toast.makeText(this@VideoStickerStudioActivity, "No se pudo leer el archivo de audio", Toast.LENGTH_SHORT).show()
                return@launch
            }

            selectedExternalAudioUri = uri
            binding.btnClearExternalAudio.visibility = if (isImageSource) View.GONE else View.VISIBLE
            binding.switchIncludeAudio.isEnabled = true
            binding.switchIncludeAudio.isChecked = true
            binding.tvAudioStatusDesc.text = getString(R.string.external_audio_selected, displayName)

            if (isImageSource) {
                mediaDurationSec = (durationMs / 1000f).coerceAtLeast(MIN_STICKER_DURATION_SEC)
                trimStartSec = 0f
                trimEndSec = min(mediaDurationSec, maxStickerDurationSec)
                configureSliderRange()
                updateTrimLabels()
            }

            updateAudioBadge(true)
            initPlayers()
            applyClippedPreview()
        }
    }

    private fun configureSliderRange() {
        isUpdatingSlider = true
        binding.rangeSliderTrim.valueFrom = 0f
        binding.rangeSliderTrim.valueTo = mediaDurationSec
        binding.rangeSliderTrim.stepSize = 0f
        binding.rangeSliderTrim.setValues(trimStartSec, trimEndSec)
        isUpdatingSlider = false
    }

    private fun readDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            -1L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Audio"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx) ?: name
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun detectAudioTrack(uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(this, uri, null)
            var found = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    found = true
                    break
                }
            }
            found
        } catch (_: Exception) {
            false
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun initPlayers() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                binding.playerView.player = this
            }
        }
        if (audioPlayer == null) {
            audioPlayer = ExoPlayer.Builder(this).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
            }
        }
    }

    private fun applyClippedPreview() {
        val startMs = (trimStartSec * 1000f).toLong().coerceAtLeast(0L)
        val endMs = (trimEndSec * 1000f).toLong().coerceAtLeast(startMs + 300L)
        val keepAudio = binding.switchIncludeAudio.isChecked && hasAnyAudio()

        if (!isImageSource) {
            val vUri = selectedVideoUri
            val player = exoPlayer
            if (vUri != null && player != null) {
                val clippedItem = MediaItem.Builder()
                    .setUri(vUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build()
                    )
                    .build()
                player.setMediaItem(clippedItem)
                player.volume = if (keepAudio && selectedExternalAudioUri == null) 1f else 0f
                player.prepare()
                player.play()
            }
        } else {
            exoPlayer?.pause()
        }

        val extAudio = selectedExternalAudioUri
        val aPlayer = audioPlayer
        if (extAudio != null && aPlayer != null) {
            val audioStartMs = if (isImageSource) startMs else 0L
            val audioEndMs = if (isImageSource) endMs else (endMs - startMs).coerceAtLeast(300L)
            val clippedAudio = MediaItem.Builder()
                .setUri(extAudio)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(audioStartMs)
                        .setEndPositionMs(audioEndMs)
                        .build()
                )
                .build()
            aPlayer.setMediaItem(clippedAudio)
            aPlayer.volume = if (keepAudio) 1f else 0f
            aPlayer.prepare()
            aPlayer.play()
        } else {
            aPlayer?.pause()
        }

        isPreviewPlaying = true
        binding.layoutPlayOverlay.visibility = View.GONE
    }

    private fun updateTrimLabels() {
        val selectedLen = (trimEndSec - trimStartSec).coerceIn(0f, maxStickerDurationSec)
        val lenText = String.format(Locale.US, "%.1fs / %.0fs", selectedLen, maxStickerDurationSec)
        binding.tvSelectedDuration.text = lenText
        binding.tvPreviewDurationBadge.text = lenText
        binding.tvStartTime.text = formatTime(trimStartSec)
        binding.tvEndTime.text = formatTime(trimEndSec)
    }

    private fun updateAudioBadge(audioActive: Boolean) {
        binding.tvPreviewAudioBadge.text = if (audioActive) "🔊 Audio" else "🔇 Sin audio"
    }

    private fun formatTime(sec: Float): String {
        val totalTenths = (sec * 10f).toInt()
        val mins = totalTenths / 600
        val remSec = (totalTenths % 600) / 10
        val tenths = totalTenths % 10
        return String.format(Locale.US, "%02d:%02d.%d", mins, remSec, tenths)
    }

    private fun exportClipForWhatsApp() {
        if (isImageSource && selectedExternalAudioUri == null) {
            Toast.makeText(this, R.string.need_audio_for_image, Toast.LENGTH_SHORT).show()
            return
        }

        exoPlayer?.pause()
        audioPlayer?.pause()
        isPreviewPlaying = false
        binding.layoutPlayOverlay.visibility = View.VISIBLE

        val startMs = (trimStartSec * 1000f).toLong().coerceAtLeast(0L)
        val endMs = (trimEndSec * 1000f).toLong().coerceAtLeast(startMs + 300L)
        val clipDurationMs = (endMs - startMs).coerceAtLeast(300L)
        val keepAudio = binding.switchIncludeAudio.isChecked && hasAnyAudio()

        val outFile = File(cacheDir, "wsm_audio_sticker_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()
        exportedCacheFile = outFile

        binding.tvExportProgressTitle.setText(R.string.exporting_video_progress)
        binding.progressExport.progress = 0
        binding.tvExportPercent.text = "0%"
        binding.layoutExportProgress.visibility = View.VISIBLE

        val layoutMode = if (isCropFill) {
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        } else {
            Presentation.LAYOUT_SCALE_TO_FIT
        }
        val presentation = Presentation.createForWidthAndHeight(512, 512, layoutMode)
        val effects = Effects(emptyList(), listOf(presentation))

        val transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    progressHandler.removeCallbacks(progressRunnable)
                    activeTransformer = null
                    saveToGallery(outFile)
                    runOnUiThread {
                        binding.layoutExportProgress.visibility = View.GONE
                        showExportSuccessDialog(outFile)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    progressHandler.removeCallbacks(progressRunnable)
                    activeTransformer = null
                    runOnUiThread {
                        binding.layoutExportProgress.visibility = View.GONE
                        Toast.makeText(
                            this@VideoStickerStudioActivity,
                            "Error al procesar: ${exportException.localizedMessage ?: "formato no soportado"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
            .build()

        activeTransformer = transformer

        val extAudio = selectedExternalAudioUri
        if (isImageSource && selectedImageUri != null && extAudio != null) {
            val imageItem = EditedMediaItem.Builder(MediaItem.fromUri(selectedImageUri!!))
                .setDurationUs(clipDurationMs * 1000L)
                .setFrameRate(24)
                .setEffects(effects)
                .build()
            val audioMediaItem = MediaItem.Builder()
                .setUri(extAudio)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            val audioEdited = EditedMediaItem.Builder(audioMediaItem).build()
            val composition = Composition.Builder(
                listOf(
                    EditedMediaItemSequence(listOf(imageItem)),
                    EditedMediaItemSequence(listOf(audioEdited))
                )
            ).build()
            transformer.start(composition, outFile.absolutePath)
        } else if (selectedVideoUri != null && extAudio != null && keepAudio) {
            val videoMediaItem = MediaItem.Builder()
                .setUri(selectedVideoUri!!)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            val videoEdited = EditedMediaItem.Builder(videoMediaItem)
                .setRemoveAudio(true)
                .setEffects(effects)
                .build()
            val audioMediaItem = MediaItem.Builder()
                .setUri(extAudio)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs(clipDurationMs)
                        .build()
                )
                .build()
            val audioEdited = EditedMediaItem.Builder(audioMediaItem).build()
            val composition = Composition.Builder(
                listOf(
                    EditedMediaItemSequence(listOf(videoEdited)),
                    EditedMediaItemSequence(listOf(audioEdited))
                )
            ).build()
            transformer.start(composition, outFile.absolutePath)
        } else if (selectedVideoUri != null) {
            val clippedItem = MediaItem.Builder()
                .setUri(selectedVideoUri!!)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()
            val editedItem = EditedMediaItem.Builder(clippedItem)
                .setRemoveAudio(!keepAudio)
                .setEffects(effects)
                .build()
            transformer.start(editedItem, outFile.absolutePath)
        }

        progressHandler.post(progressRunnable)
    }

    private fun cancelExport() {
        progressHandler.removeCallbacks(progressRunnable)
        activeTransformer?.cancel()
        activeTransformer = null
        webpExportJob?.cancel()
        webpExportJob = null
        binding.layoutExportProgress.visibility = View.GONE
    }

    private fun showChoosePackAndSaveWebp() {
        if (selectedVideoUri == null) return
        val compatiblePacks = StickerStorage.getAllPacks(this)
            .filter { (it.stickers.isEmpty() || it.animatedStickerPack) && it.stickers.size < 30 }

        if (compatiblePacks.isEmpty()) {
            val autoPack = createAutoAnimatedPack()
            encodeAndSaveWebpToPack(autoPack)
            return
        }

        val items = compatiblePacks.map { "${it.name} (${it.stickers.size}/30)" }.toMutableList()
        items.add(getString(R.string.auto_new_animated_pack))
        items.add(getString(R.string.create_new_animated_pack))

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_pack_title)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    items.lastIndex -> showCreateAnimatedPackDialog()
                    items.lastIndex - 1 -> encodeAndSaveWebpToPack(createAutoAnimatedPack())
                    else -> encodeAndSaveWebpToPack(compatiblePacks[which])
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_liquid_glass_dialog)
        dialog.show()
    }

    private fun createAutoAnimatedPack(): StickerPack {
        val baseName = getString(R.string.default_video_pack_name)
        val existingNames = StickerStorage.getAllPacks(this).map { it.name }.toSet()
        var finalName = baseName
        var counter = 2
        while (existingNames.contains(finalName)) {
            finalName = "$baseName $counter"
            counter++
        }
        val newPack = StickerPack(
            identifier = UUID.randomUUID().toString(),
            name = finalName,
            publisher = "Personal",
            trayImageFile = "",
            animatedStickerPack = true
        )
        StickerStorage.savePack(this, newPack)
        return newPack
    }

    private fun showCreateAnimatedPackDialog() {
        val dialogBinding = DialogCreatePackBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_liquid_glass_dialog)

        dialogBinding.btnCancel.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnCreatePack.setOnClickListener {
            val name = dialogBinding.etPackName.text?.toString()?.trim().orEmpty()
            val publisher = dialogBinding.etPublisher.text?.toString()?.trim().orEmpty()

            if (name.isEmpty()) {
                dialogBinding.tilPackName.error = "Escribe un nombre para el paquete"
                dialogBinding.etPackName.requestFocus()
                return@setOnClickListener
            }

            val newPack = StickerPack(
                identifier = UUID.randomUUID().toString(),
                name = name,
                publisher = if (publisher.isEmpty()) "Personal" else publisher,
                trayImageFile = "",
                animatedStickerPack = true
            )
            StickerStorage.savePack(this, newPack)
            dialog.dismiss()
            encodeAndSaveWebpToPack(newPack)
        }
        dialog.show()
    }

    private fun encodeAndSaveWebpToPack(targetPack: StickerPack) {
        val uri = selectedVideoUri ?: return
        exoPlayer?.pause()
        audioPlayer?.pause()
        isPreviewPlaying = false
        binding.layoutPlayOverlay.visibility = View.VISIBLE

        val startMs = (trimStartSec * 1000f).toLong().coerceAtLeast(0L)
        val endMs = (trimEndSec * 1000f).toLong().coerceAtLeast(startMs + 300L)

        val outWebp = File(cacheDir, "wsm_anim_${System.currentTimeMillis()}.webp")
        if (outWebp.exists()) outWebp.delete()

        binding.tvExportProgressTitle.setText(R.string.exporting_webp_progress)
        binding.progressExport.progress = 0
        binding.tvExportPercent.text = "0%"
        binding.layoutExportProgress.visibility = View.VISIBLE

        webpExportJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                AnimatedWebpEncoder.encodeVideoToAnimatedWebp(
                    context = this@VideoStickerStudioActivity,
                    videoUri = uri,
                    startMs = startMs,
                    endMs = endMs,
                    isCropFill = isCropFill,
                    outputFile = outWebp
                ) { pct ->
                    runOnUiThread {
                        binding.progressExport.progress = pct
                        binding.tvExportPercent.text = "$pct%"
                    }
                }
            }

            if (!result.success) {
                binding.layoutExportProgress.visibility = View.GONE
                Toast.makeText(
                    this@VideoStickerStudioActivity,
                    result.errorMessage ?: "Error al crear sticker .webp",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val added = withContext(Dispatchers.IO) {
                StickerStorage.addStickerToPack(
                    this@VideoStickerStudioActivity,
                    targetPack.identifier,
                    Uri.fromFile(outWebp)
                )
            }
            outWebp.delete()
            binding.layoutExportProgress.visibility = View.GONE

            if (added) {
                val sizeKb = String.format(Locale.US, "%.0f KB", result.fileSizeBytes / 1024.0)
                val successDialog = MaterialAlertDialogBuilder(this@VideoStickerStudioActivity)
                    .setTitle("✅ Sticker (.webp)")
                    .setMessage(getString(R.string.sticker_saved_to_pack_msg, targetPack.name, sizeKb))
                    .setPositiveButton(R.string.open_pack) { _, _ ->
                        val intent = Intent(this@VideoStickerStudioActivity, PackDetailActivity::class.java).apply {
                            putExtra(PackDetailActivity.EXTRA_PACK_ID, targetPack.identifier)
                        }
                        startActivity(intent)
                        finish()
                    }
                    .setNegativeButton(R.string.done, null)
                    .create()
                successDialog.window?.setBackgroundDrawableResource(R.drawable.bg_liquid_glass_dialog)
                successDialog.show()
            } else {
                Toast.makeText(
                    this@VideoStickerStudioActivity,
                    "No se pudo añadir el sticker al paquete",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun saveToGallery(sourceFile: File) {
        val fileName = "WSM_Sticker_${System.currentTimeMillis()}.mp4"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/StickerMaker")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val itemUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (itemUri != null) {
                    contentResolver.openOutputStream(itemUri)?.use { out ->
                        FileInputStream(sourceFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(itemUri, values, null, null)
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "StickerMaker"
                )
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, fileName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(dest).use { out ->
                        input.copyTo(out)
                    }
                }
            }
        } catch (_: Exception) {
            // Even if MediaStore fails, cache FileProvider sharing remains available
        }
    }

    private fun showExportSuccessDialog(clipFile: File) {
        val sizeKb = clipFile.length() / 1024.0
        val sizeLabel = if (sizeKb >= 1024) {
            String.format(Locale.US, "%.1f MB", sizeKb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f KB", sizeKb)
        }
        val durationSec = (trimEndSec - trimStartSec).coerceIn(0f, maxStickerDurationSec)
        val audioTag = if (binding.switchIncludeAudio.isChecked && hasAnyAudio()) "🔊 Con Audio" else "🔇 Sin Audio"

        val message = String.format(
            Locale.US,
            "• Duración: %.1fs | Tamaño: %s | 512×512\n• %s\n\n%s",
            durationSec,
            sizeLabel,
            audioTag,
            getString(R.string.export_success_msg)
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_success_title)
            .setMessage(message)
            .setPositiveButton(R.string.share_to_whatsapp) { _, _ ->
                shareClipToWhatsApp(clipFile)
            }
            .setNegativeButton(R.string.done, null)
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_liquid_glass_dialog)
        dialog.show()
    }

    private fun shareClipToWhatsApp(clipFile: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            clipFile
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(sendIntent)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(fallback, getString(R.string.share_to_whatsapp)))
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        audioPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        activeTransformer?.cancel()
        webpExportJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        audioPlayer?.release()
        audioPlayer = null
    }
}
