package com.pianorain.ui

import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RecordingDialog(onClose: () -> Unit, onSave: (File, String) -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val file = remember { File(context.cacheDir, "piano-recording-${java.util.UUID.randomUUID()}.m4a") }
    var name by remember { mutableStateOf(SimpleDateFormat("'Recording' yyyy-MM-dd HH-mm-ss", Locale.getDefault()).format(Date())) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    var phase by remember { mutableStateOf("idle") }
    var elapsed by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var transferred by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun releasePreview() { preview?.release(); preview = null }
    fun discard() {
        releasePreview()
        recorder?.release(); recorder = null
        file.delete()
        onClose()
    }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val focusRequest = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) discard()
            }.build()
    }
    fun stop(): Boolean {
        val current = recorder ?: return phase == "stopped"
        return try {
            current.stop()
            phase = "stopped"
            true
        } catch (_: Exception) {
            error = "Recording was too short or could not be saved. Please record again."
            phase = "idle"; file.delete(); elapsed = 0
            false
        } finally {
            current.release(); recorder = null; level = 0f
            audioManager.abandonAudioFocusRequest(focusRequest)
        }
    }
    fun requestClose() { if (phase == "idle") discard() else confirmDiscard = true }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) discard()
        }
        lifecycle.addObserver(observer)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            lifecycle.removeObserver(observer)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            preview?.release(); recorder?.release()
            audioManager.abandonAudioFocusRequest(focusRequest)
            if (!transferred) file.delete()
        }
    }
    LaunchedEffect(phase) {
        while (phase == "recording") {
            delay(100)
            val now = SystemClock.elapsedRealtime()
            elapsed += now - startedAt; startedAt = now
            level = (runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) / 32767f).coerceIn(0f, 1f)
            if (elapsed >= 600_000L || file.usableSpace < 20L * 1024 * 1024) {
                stop()
                error = "Recording stopped at the duration or storage limit. Preview and save your take."
            }
        }
    }
    AlertDialog(onDismissRequest = { requestClose() },
        title = { Text("Record piano audio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(100) },
                    label = { Text("Song name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("%d:%02d / 10:00".format(elapsed / 60000, elapsed / 1000 % 60))
                Text(when (phase) { "recording" -> "Recording"; "paused" -> "Paused"; "stopped" -> "Ready to save"; else -> "Record solo piano in a quiet room." })
                LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                Text("Audio stays on this device. Leaving the app discards this take.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (phase != "stopped") Button(onClick = {
                        error = null
                        try {
                            when (phase) {
                                "idle" -> {
                                    require(context.cacheDir.usableSpace > 500L * 1024 * 1024) { "Free at least 500 MB before recording." }
                                    check(audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                                        "Audio is in use. Try recording again when it is available."
                                    }
                                    @Suppress("DEPRECATION")
                                    val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                                    recorder = next
                                    next.setAudioSource(MediaRecorder.AudioSource.MIC)
                                    next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                    next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                    next.setAudioChannels(1)
                                    next.setAudioSamplingRate(44100)
                                    next.setAudioEncodingBitRate(192000)
                                    next.setMaxDuration(600_000)
                                    next.setMaxFileSize(100L * 1024 * 1024)
                                    next.setOutputFile(file.absolutePath)
                                    next.setOnInfoListener { _, what, _ ->
                                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
                                            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop()
                                    }
                                    next.setOnErrorListener { _, _, _ ->
                                        recorder?.release(); recorder = null
                                        file.delete(); phase = "idle"; elapsed = 0
                                        error = "Recording was interrupted. Please start again."
                                    }
                                    next.prepare(); next.start()
                                    elapsed = 0; startedAt = SystemClock.elapsedRealtime(); phase = "recording"
                                }
                                "recording" -> {
                                    recorder?.pause()
                                    elapsed += SystemClock.elapsedRealtime() - startedAt
                                    phase = "paused"; level = 0f
                                }
                                "paused" -> {
                                    recorder?.resume()
                                    startedAt = SystemClock.elapsedRealtime(); phase = "recording"
                                }
                            }
                        } catch (failure: Exception) {
                            recorder?.release(); recorder = null
                            audioManager.abandonAudioFocusRequest(focusRequest)
                            file.delete(); phase = "idle"; elapsed = 0
                            error = failure.message ?: "Unable to access the microphone."
                        }
                    }) { Text(when (phase) { "recording" -> "Pause"; "paused" -> "Resume"; else -> "Record" }) }
                    if (phase == "recording" || phase == "paused") OutlinedButton(onClick = { stop() }) { Text("Stop") }
                    if (phase == "stopped") OutlinedButton(onClick = {
                        if (preview != null) releasePreview()
                        else try {
                            val player = MediaPlayer(); preview = player
                            player.setDataSource(file.absolutePath)
                            player.setOnCompletionListener { releasePreview() }
                            player.setOnErrorListener { _, _, _ ->
                                releasePreview(); error = "Unable to preview this recording."; true
                            }
                            player.prepare(); player.start()
                        } catch (_: Exception) { releasePreview(); error = "Unable to preview this recording." }
                    }) { Text(if (preview == null) "Play" else "Stop preview") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = phase != "idle" && name.isNotBlank(), onClick = {
            releasePreview()
            if (stop()) { transferred = true; onSave(file, name) }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { requestClose() }) { Text("Discard") } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard this recording?") }, text = { Text("This take has not been saved.") },
        confirmButton = { TextButton(onClick = { discard() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep recording") } })
}
