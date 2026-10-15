package app.wakelink.android.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wakelink.android.BuildConfig
import app.wakelink.android.R
import app.wakelink.android.ui.OnResume
import kotlinx.coroutines.launch
import java.io.File

private sealed interface Step {
    data object Prompt : Step
    data class Downloading(val progress: Float?) : Step
    data class NeedsPermission(val apk: File) : Step
    data class Failed(val message: String) : Step
}

/**
 * "New version available" flow: release notes -> download with progress
 * -> (one-time "install unknown apps" grant) -> system installer.
 * [onSkip], when given, adds a "skip" button that silences this version (used by the
 * automatic startup check, not the manual one in Settings).
 */
@Composable
fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit, onSkip: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Prompt) }

    fun installOrAskPermission(apk: File) {
        if (AppUpdater.canInstall(context)) {
            AppUpdater.install(context, apk)
            onDismiss()
        } else {
            step = Step.NeedsPermission(apk)
        }
    }

    // Coming back from the "install unknown apps" settings screen.
    OnResume {
        val current = step
        if (current is Step.NeedsPermission && AppUpdater.canInstall(context)) installOrAskPermission(current.apk)
    }

    val downloading = step is Step.Downloading
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(R.string.update_title, info.versionName)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val s = step) {
                    Step.Prompt -> {
                        Text(stringResource(R.string.update_current, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
                        if (info.notes.isNotBlank()) {
                            ReleaseNotesView(info.notes, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    is Step.Downloading -> {
                        Text(stringResource(R.string.update_downloading))
                        if (s.progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        } else {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        }
                    }
                    is Step.NeedsPermission -> Text(stringResource(R.string.update_needs_permission))
                    is Step.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (val s = step) {
                Step.Prompt, is Step.Failed -> TextButton(onClick = {
                    step = Step.Downloading(null)
                    scope.launch {
                        try {
                            val apk = AppUpdater.download(context, info) { p -> step = Step.Downloading(p) }
                            installOrAskPermission(apk)
                        } catch (e: Exception) {
                            step = Step.Failed(context.getString(R.string.update_download_failed, e.message ?: context.getString(R.string.common_unknown_error)))
                        }
                    }
                }) { Text(stringResource(if (s is Step.Failed) R.string.update_retry else R.string.update_install)) }
                is Step.NeedsPermission -> TextButton(onClick = { AppUpdater.openInstallPermissionSettings(context) }) {
                    Text(stringResource(R.string.update_grant_permission))
                }
                is Step.Downloading -> {}
            }
        },
        dismissButton = {
            if (!downloading) {
                Row {
                    if (onSkip != null && step == Step.Prompt) {
                        TextButton(onClick = onSkip) { Text(stringResource(R.string.update_skip)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
                }
            }
        },
    )
}
