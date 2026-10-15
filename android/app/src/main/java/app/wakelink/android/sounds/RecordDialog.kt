package app.wakelink.android.sounds

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.wakelink.android.R
import kotlinx.coroutines.delay
import java.io.File

private enum class RecState { IDLE, RECORDING, RECORDED }

/** Records up to [CustomSounds.MAX_RECORDING_MS] of voice (AAC in MP4), lets the user replay and name it. */
@Composable
fun RecordDialog(onDismiss: () -> Unit, onSave: (name: String, bytes: ByteArray) -> Unit) {
    val context = LocalContext.current
    val file = remember { File(context.cacheDir, "recording.m4a") }
    var state by remember { mutableStateOf(RecState.IDLE) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var name by remember { mutableStateOf(context.getString(R.string.sounds_recording_default_name)) }
    var error by remember { mutableStateOf<String?>(null) }

    fun stopRecording() {
        recorder?.let { r ->
            val ok = runCatching { r.stop() }.isSuccess
            r.release()
            state = if (ok && file.length() > 0) RecState.RECORDED else RecState.IDLE
            if (!ok) error = context.getString(R.string.sounds_record_failed)
        }
        recorder = null
    }

    fun startRecording() {
        error = null
        file.delete()
        val r = newRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(CustomSounds.MAX_RECORDING_MS)
            r.setOutputFile(file.absolutePath)
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording()
            }
            r.prepare()
            r.start()
            recorder = r
            startedAt = System.currentTimeMillis()
            elapsedMs = 0
            state = RecState.RECORDING
        } catch (_: Exception) {
            r.release()
            error = context.getString(R.string.sounds_record_failed)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = context.getString(R.string.sounds_mic_denied)
    }

    LaunchedEffect(state) {
        while (state == RecState.RECORDING) {
            elapsedMs = System.currentTimeMillis() - startedAt
            delay(100)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder?.let { runCatching { it.stop() }; it.release() }
            SoundPreview.stop()
        }
    }

    AlertDialog(
        onDismissRequest = { if (state != RecState.RECORDING) onDismiss() },
        icon = { Icon(Icons.Rounded.Mic, contentDescription = null) },
        title = { Text(stringResource(R.string.sounds_record_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.sounds_record_body, CustomSounds.MAX_RECORDING_MS / 1000),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state == RecState.RECORDING) {
                    LinearProgressIndicator(
                        progress = { (elapsedMs.toFloat() / CustomSounds.MAX_RECORDING_MS).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("%.1f s".format(elapsedMs / 1000f), style = MaterialTheme.typography.labelLarge)
                }
                when (state) {
                    RecState.IDLE -> Button(onClick = {
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text(stringResource(R.string.sounds_record_start)) }
                    RecState.RECORDING -> Button(onClick = ::stopRecording) { Text(stringResource(R.string.sounds_record_stop)) }
                    RecState.RECORDED -> {
                        OutlinedButton(onClick = {
                            SoundPreview.playUri(
                                context, Uri.fromFile(file), loop = false,
                                durationMs = CustomSounds.MAX_RECORDING_MS + 1_000L,
                            )
                        }) { Text(stringResource(R.string.sounds_record_play)) }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(CustomSounds.MAX_NAME_LENGTH) },
                            singleLine = true,
                            label = { Text(stringResource(R.string.sounds_name_label)) },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (state == RecState.RECORDED) {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    SoundPreview.stop()
                    onSave(name.trim(), file.readBytes())
                }) { Text(stringResource(R.string.sounds_save)) }
            }
        },
        dismissButton = {
            if (state != RecState.RECORDING) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

private fun newRecorder(context: Context): MediaRecorder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
    else @Suppress("DEPRECATION") MediaRecorder()
