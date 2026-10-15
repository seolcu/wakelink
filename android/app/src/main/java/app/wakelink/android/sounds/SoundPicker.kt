package app.wakelink.android.sounds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wakelink.android.R
import app.wakelink.android.crypto.MediaKeyStore
import app.wakelink.android.network.MediaView
import app.wakelink.android.network.WakeLinkApi
import app.wakelink.android.network.orThrow
import app.wakelink.android.network.toUserMessage
import app.wakelink.android.ui.components.StatusKind
import app.wakelink.android.ui.components.StatusPill
import kotlinx.coroutines.launch

/**
 * Bottom sheet for picking an alarm sound, with previews.
 *
 * Receiver side ([api] null): the device tone and built-in sounds, for
 * "my default sound". Sender side ([api] given): additionally "let them
 * choose" (key null) and the user's own recordings/files, which can be
 * recorded, imported and deleted right here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPickerSheet(
    title: String,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    api: WakeLinkApi? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mediaKeys = remember { MediaKeyStore(context) }
    var custom by remember { mutableStateOf<List<MediaView>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }

    suspend fun reload() {
        if (api == null) return
        try {
            custom = api.listSounds()
        } catch (e: Exception) {
            error = context.getString(R.string.sounds_load_failed, e.toUserMessage(context))
        }
    }
    LaunchedEffect(Unit) { reload() }
    DisposableEffect(Unit) { onDispose { SoundPreview.stop() } }

    fun upload(kind: String, name: String, bytes: ByteArray, mime: String) {
        if (api == null) return
        busy = true
        error = null
        scope.launch {
            try {
                val created = CustomSounds.upload(context, api, kind, name, bytes, mime)
                reload()
                onSelect(SoundRef.Custom(created.id, null).key)
            } catch (e: Exception) {
                error = context.getString(R.string.sounds_upload_failed, e.toUserMessage(context))
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val picked = CustomSounds.readImport(context, uri)) {
            is CustomSounds.Import.TooLarge -> error = context.getString(R.string.sounds_too_large)
            is CustomSounds.Import.Unreadable -> error = context.getString(R.string.sounds_unreadable)
            is CustomSounds.Import.Ok -> upload("file", picked.name, picked.bytes, picked.mime)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            if (api != null) {
                SoundRow(stringResource(R.string.sounds_their_choice), selected == null, onClick = { onSelect(null) })
            }
            SoundRow(
                stringResource(R.string.sounds_device_default),
                selected == SoundRef.Device.key,
                onClick = { onSelect(SoundRef.Device.key) },
                onPreview = { SoundPreview.play(context, SoundRef.Device) },
            )
            PRESET_SOUNDS.forEach { preset ->
                val ref = SoundRef.Preset(preset.name)
                SoundRow(
                    stringResource(preset.label),
                    selected == ref.key,
                    onClick = { onSelect(ref.key) },
                    onPreview = { SoundPreview.play(context, ref) },
                )
            }

            if (api != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.sounds_mine),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                when (val list = custom) {
                    null -> if (error == null) {
                        CircularProgressIndicator(modifier = Modifier.padding(24.dp).size(24.dp))
                    }
                    else -> {
                        if (list.isEmpty()) {
                            Text(
                                stringResource(R.string.sounds_mine_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp),
                            )
                        }
                        list.forEach { s ->
                            val ref = SoundRef.Custom(s.id, null)
                            // Encrypted on another install: this phone can't decrypt or send it.
                            val usable = !s.isEncrypted() || mediaKeys.get(s.id) != null
                            SoundRow(
                                if (usable) s.name else stringResource(R.string.media_unusable, s.name),
                                selected == ref.key,
                                onClick = { if (usable) onSelect(ref.key) },
                                onPreview = if (!usable) null else {
                                    {
                                        scope.launch {
                                            val full = SoundRef.Custom(s.id, s.sha256)
                                            SoundCache.obtain(context, full, 10_000, mediaKeys.get(s.id))
                                            SoundPreview.play(context, full)
                                        }
                                    }
                                },
                                onDelete = {
                                    scope.launch {
                                        try {
                                            api.deleteSound(s.id).orThrow()
                                            mediaKeys.remove(s.id)
                                            if (selected == ref.key) onSelect(null)
                                            reload()
                                        } catch (e: Exception) {
                                            error = context.getString(R.string.sounds_delete_failed, e.toUserMessage(context))
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    OutlinedButton(enabled = !busy, onClick = { recording = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.sounds_record), modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { importLauncher.launch(arrayOf("audio/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.sounds_import), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Text(
                    stringResource(R.string.sounds_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(24.dp).size(24.dp))
            }
            error?.let {
                StatusPill(StatusKind.ERROR, it, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
    }

    if (recording) {
        RecordDialog(
            onDismiss = { recording = false },
            onSave = { name, bytes ->
                recording = false
                upload("voice", name, bytes, CustomSounds.RECORDING_MIME)
            },
        )
    }
}

@Composable
private fun SoundRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    onPreview: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        RadioButton(selected = checked, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        onPreview?.let {
            IconButton(onClick = it) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.sounds_preview))
            }
        }
        onDelete?.let {
            IconButton(onClick = it) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.sounds_delete))
            }
        }
    }
}

/** Display label for a sound key, e.g. on the per-contact chip. */
@Composable
fun soundLabel(key: String?, custom: List<MediaView>? = null): String = when (val ref = parseSoundRef(key)) {
    null -> stringResource(R.string.sounds_their_choice_short)
    SoundRef.Device -> stringResource(R.string.sounds_device_default)
    is SoundRef.Preset -> presetSound(ref.name)?.let { stringResource(it.label) } ?: ref.name
    is SoundRef.Custom -> custom?.firstOrNull { it.id == ref.id }?.name ?: stringResource(R.string.sounds_mine_short)
}

/** Uploaded by an app with end-to-end encryption: only openable with its content key. */
fun MediaView.isEncrypted(): Boolean = mime == CustomSounds.ENCRYPTED_MIME
