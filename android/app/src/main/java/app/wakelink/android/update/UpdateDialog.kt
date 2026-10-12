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
import androidx.compose.ui.unit.dp
import app.wakelink.android.BuildConfig
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
        title = { Text("새 버전 ${info.versionName}") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (val s = step) {
                    Step.Prompt -> {
                        Text("지금 버전: ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                        if (info.notes.isNotBlank()) {
                            Text(
                                info.notes,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    is Step.Downloading -> {
                        Text("내려받는 중…")
                        if (s.progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                        } else {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                        }
                    }
                    is Step.NeedsPermission -> Text(
                        "업데이트를 설치하려면 WakeLink에 '출처를 알 수 없는 앱 설치' 권한을 한 번 허용해야 해요. " +
                            "허용하고 돌아오면 바로 설치 화면이 열려요."
                    )
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
                            step = Step.Failed("내려받지 못했어요: ${e.message ?: "알 수 없는 오류"}")
                        }
                    }
                }) { Text(if (s is Step.Failed) "다시 시도" else "업데이트") }
                is Step.NeedsPermission -> TextButton(onClick = { AppUpdater.openInstallPermissionSettings(context) }) {
                    Text("권한 허용하러 가기")
                }
                is Step.Downloading -> {}
            }
        },
        dismissButton = {
            if (!downloading) {
                Row {
                    if (onSkip != null && step == Step.Prompt) {
                        TextButton(onClick = onSkip) { Text("건너뛰기") }
                    }
                    TextButton(onClick = onDismiss) { Text("나중에") }
                }
            }
        },
    )
}
