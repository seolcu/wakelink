package app.wakelink.android.settings

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.wakelink.android.alarm.AlarmForegroundService
import app.wakelink.android.data.DismissMode
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.status.ArmedStatusNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }

    var armed by remember { mutableStateOf(true) }
    var dndBypass by remember { mutableStateOf(false) }
    var dismissMode by remember { mutableStateOf(DismissMode.UNLOCK_REQUIRED) }
    var maxVolume by remember { mutableStateOf(false) }
    var dndAccessGranted by remember { mutableStateOf(false) }

    fun refreshDndAccess() {
        val nm = context.getSystemService(NotificationManager::class.java)
        dndAccessGranted = nm.isNotificationPolicyAccessGranted
    }

    LaunchedEffect(Unit) {
        armed = settings.armed.first()
        dndBypass = settings.dndBypassEnabled.first()
        dismissMode = settings.dismissMode.first()
        maxVolume = settings.maxVolumeOnRing.first()
        refreshDndAccess()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        TextButton(onClick = onDone) { Text("닫기") }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            Text("활성화")
            Switch(
                checked = armed,
                onCheckedChange = { value ->
                    armed = value
                    scope.launch {
                        settings.setArmed(value)
                        ArmedStatusNotifier.update(context, value)
                    }
                },
            )
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            Text("DND 완전 무음도 뚫기")
            Switch(
                checked = dndBypass,
                onCheckedChange = { value ->
                    if (value && !dndAccessGranted) {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    }
                    dndBypass = value
                    scope.launch { settings.setDndBypassEnabled(value) }
                },
            )
        }
        if (dndBypass && !dndAccessGranted) {
            Text("설정 > 알림 액세스 권한에서 WakeLink를 허용해야 동작합니다")
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            Text("해제 방식: ${if (dismissMode == DismissMode.UNLOCK_REQUIRED) "잠금해제 필요" else "탭하면 꺼짐"}")
            Switch(
                checked = dismissMode == DismissMode.TAP_TO_DISMISS,
                onCheckedChange = { tapToDismiss ->
                    val mode = if (tapToDismiss) DismissMode.TAP_TO_DISMISS else DismissMode.UNLOCK_REQUIRED
                    dismissMode = mode
                    scope.launch { settings.setDismissMode(mode) }
                },
            )
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            Text("알람 볼륨 강제 최대화")
            Switch(
                checked = maxVolume,
                onCheckedChange = { value ->
                    maxVolume = value
                    scope.launch { settings.setMaxVolumeOnRing(value) }
                },
            )
        }

        Button(
            modifier = Modifier.padding(top = 24.dp),
            onClick = {
                val pm = context.getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                }
            },
        ) {
            Text("배터리 최적화 제외 요청")
        }

        Button(
            modifier = Modifier.padding(top = 16.dp),
            onClick = {
                val intent = Intent(context, AlarmForegroundService::class.java).apply {
                    putExtra(AlarmForegroundService.EXTRA_TRIGGER_ID, -1L)
                    putExtra(AlarmForegroundService.EXTRA_FROM_DISPLAY_NAME, "테스트")
                }
                ContextCompat.startForegroundService(context, intent)
            },
        ) {
            Text("테스트 알람 울리기")
        }
    }
}
