package app.wakelink.android.settings

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.wakelink.android.alarm.AlarmForegroundService
import app.wakelink.android.data.DismissMode
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.PatchMeRequest
import app.wakelink.android.network.orThrow
import app.wakelink.android.network.toUserMessage
import app.wakelink.android.pairing.MAX_DISPLAY_NAME_LENGTH
import app.wakelink.android.status.ArmedStatusNotifier
import app.wakelink.android.status.DeviceReadiness
import app.wakelink.android.ui.OnResume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onDone: () -> Unit, onAccountDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val tokenStore = remember { TokenStore(context) }
    val api = remember { ApiClient.create(tokenStore) }

    var armed by remember { mutableStateOf(true) }
    var dndBypass by remember { mutableStateOf(false) }
    var dismissMode by remember { mutableStateOf(DismissMode.UNLOCK_REQUIRED) }
    var maxVolume by remember { mutableStateOf(false) }
    var dndAccessGranted by remember { mutableStateOf(false) }
    var readiness by remember { mutableStateOf<DeviceReadiness?>(null) }

    var myName by remember { mutableStateOf<String?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        armed = settings.armed.first()
        dndBypass = settings.dndBypassEnabled.first()
        dismissMode = settings.dismissMode.first()
        maxVolume = settings.maxVolumeOnRing.first()
        try {
            myName = api.getMe().displayName
        } catch (e: Exception) {
            accountError = "계정 정보를 불러오지 못했어요: ${e.toUserMessage()}"
        }
    }

    // Permissions are granted/revoked in the system Settings app, so
    // re-check every time we come back from there.
    OnResume {
        dndAccessGranted = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        readiness = DeviceReadiness.check(context)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onDone) { Text("닫기") }

        SectionTitle("계정")
        Text("내 이름: ${myName ?: "…"}", modifier = Modifier.padding(top = 8.dp))
        accountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(enabled = myName != null, onClick = { renaming = true }) { Text("이름 변경") }
            TextButton(modifier = Modifier.padding(start = 8.dp), onClick = { confirmDelete = true }) {
                Text("계정 삭제", color = MaterialTheme.colorScheme.error)
            }
        }

        SectionTitle("알람")
        SettingSwitch("활성화 (끄면 아무도 나를 깨울 수 없어요)", armed) { value ->
            armed = value
            scope.launch {
                settings.setArmed(value)
                ArmedStatusNotifier.update(context, value)
            }
        }

        SettingSwitch("방해 금지 '완전 무음'도 뚫기", dndBypass) { value ->
            if (value && !dndAccessGranted) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
            dndBypass = value
            scope.launch { settings.setDndBypassEnabled(value) }
        }
        if (dndBypass && !dndAccessGranted) {
            Text(
                "설정 > 방해 금지 액세스 권한에서 WakeLink를 허용해야 동작합니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SettingSwitch(
            "탭하면 바로 꺼짐 (끄면 잠금 해제해야 꺼짐)",
            dismissMode == DismissMode.TAP_TO_DISMISS,
        ) { tapToDismiss ->
            val mode = if (tapToDismiss) DismissMode.TAP_TO_DISMISS else DismissMode.UNLOCK_REQUIRED
            dismissMode = mode
            scope.launch { settings.setDismissMode(mode) }
        }

        SettingSwitch("울릴 때 알람 볼륨 최대로", maxVolume) { value ->
            maxVolume = value
            scope.launch { settings.setMaxVolumeOnRing(value) }
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

        SectionTitle("기기 준비 상태")
        readiness?.let { r ->
            ReadinessRow("알림 허용", r.notificationsEnabled) {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
            ReadinessRow("잠금 화면에 전체 화면 알람 표시", r.fullScreenIntentAllowed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                    )
                }
            }
            ReadinessRow("배터리 최적화 제외 (자는 동안에도 바로 받기)", r.ignoringBatteryOptimizations) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }
        }
    }

    if (renaming) {
        var draft by remember { mutableStateOf(myName ?: "") }
        var renameError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("이름 변경") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(MAX_DISPLAY_NAME_LENGTH) },
                        singleLine = true,
                    )
                    renameError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            myName = api.patchMe(PatchMeRequest(displayName = draft.trim())).displayName
                            renaming = false
                        } catch (e: Exception) {
                            renameError = e.toUserMessage()
                        }
                    }
                }) { Text("저장") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("취소") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("계정을 삭제할까요?") },
            text = { Text("모든 연결과 기록이 삭제되고 되돌릴 수 없어요. 다시 쓰려면 처음부터 등록하고 초대 코드를 다시 주고받아야 해요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            api.deleteMe().orThrow()
                            tokenStore.clear()
                            onAccountDeleted()
                        } catch (e: Exception) {
                            accountError = "계정 삭제 실패: ${e.toUserMessage()}"
                        }
                    }
                }) { Text("삭제", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ReadinessRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Text(
            "${if (ok) "✓" else "✗"} $label",
            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        if (!ok) TextButton(onClick = onFix) { Text("설정") }
    }
}
