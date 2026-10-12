package app.wakelink.android.settings

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneLocked
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.wakelink.android.BuildConfig
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
import app.wakelink.android.ui.components.Avatar
import app.wakelink.android.ui.components.SectionCard
import app.wakelink.android.ui.components.SettingsRow
import app.wakelink.android.ui.components.StatusKind
import app.wakelink.android.ui.components.StatusPill
import app.wakelink.android.ui.theme.WakeTheme
import app.wakelink.android.update.AppUpdater
import app.wakelink.android.update.UpdateDialog
import app.wakelink.android.update.UpdateInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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
    var confirmRotateKey by remember { mutableStateOf(false) }
    var keyRotated by remember { mutableStateOf(false) }

    var autoUpdateCheck by remember { mutableStateOf(true) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        armed = settings.armed.first()
        dndBypass = settings.dndBypassEnabled.first()
        dismissMode = settings.dismissMode.first()
        maxVolume = settings.maxVolumeOnRing.first()
        autoUpdateCheck = settings.autoUpdateCheck.first()
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "뒤로") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            SectionCard("계정") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Avatar(myName ?: "?", size = 48.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(myName ?: "불러오는 중…", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "연결된 사람에게 보이는 이름",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(enabled = myName != null, onClick = { renaming = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "이름 변경")
                    }
                }
                accountError?.let {
                    StatusPill(StatusKind.ERROR, it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    icon = Icons.Rounded.Key,
                    title = "API 키 재발급",
                    supporting = if (keyRotated) "새 키로 바꿨어요. 이전 키는 더 이상 쓸 수 없어요" else "키가 유출된 것 같을 때만 쓰세요. 연결은 그대로 유지돼요",
                    onClick = { confirmRotateKey = true },
                )
                SettingsRow(
                    icon = Icons.Rounded.DeleteForever,
                    title = "계정 삭제",
                    supporting = "모든 연결과 기록이 지워져요",
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmDelete = true },
                )
            }

            SectionCard("알람 받기") {
                SettingsRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    title = "활성화",
                    supporting = if (armed) "연결된 사람이 나를 깨울 수 있어요" else "꺼져 있어 아무도 나를 깨울 수 없어요",
                    trailing = {
                        Switch(checked = armed, onCheckedChange = { value ->
                            armed = value
                            scope.launch {
                                settings.setArmed(value)
                                ArmedStatusNotifier.update(context, value)
                            }
                        })
                    },
                )
                SettingsRow(
                    icon = Icons.Rounded.DoNotDisturbOn,
                    title = "방해 금지 '완전 무음'도 뚫기",
                    supporting = if (dndBypass && !dndAccessGranted) {
                        "방해 금지 액세스 권한에서 WakeLink를 허용해야 동작해요"
                    } else {
                        "울리는 동안만 바꾸고, 끝나면 되돌려요"
                    },
                    trailing = {
                        Switch(checked = dndBypass, onCheckedChange = { value ->
                            if (value && !dndAccessGranted) {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            }
                            dndBypass = value
                            scope.launch { settings.setDndBypassEnabled(value) }
                        })
                    },
                )
                SettingsRow(
                    icon = Icons.Rounded.TouchApp,
                    title = "탭하면 바로 끄기",
                    supporting = if (dismissMode == DismissMode.TAP_TO_DISMISS) "잠금 해제 없이 버튼만 누르면 꺼져요" else "끄려면 잠금을 해제해야 해요",
                    trailing = {
                        Switch(checked = dismissMode == DismissMode.TAP_TO_DISMISS, onCheckedChange = { tapToDismiss ->
                            val mode = if (tapToDismiss) DismissMode.TAP_TO_DISMISS else DismissMode.UNLOCK_REQUIRED
                            dismissMode = mode
                            scope.launch { settings.setDismissMode(mode) }
                        })
                    },
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = "울릴 때 알람 볼륨 최대로",
                    supporting = "끝나면 원래 볼륨으로 돌아가요",
                    trailing = {
                        Switch(checked = maxVolume, onCheckedChange = { value ->
                            maxVolume = value
                            scope.launch { settings.setMaxVolumeOnRing(value) }
                        })
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
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

            readiness?.let { r ->
                SectionCard("기기 준비 상태") {
                    ReadinessRow(
                        Icons.Rounded.NotificationsActive, "알림 허용", "꺼져 있으면 알람이 표시되지 않아요", r.notificationsEnabled,
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                    ReadinessRow(
                        Icons.Rounded.PhoneLocked, "잠금 화면 전체 화면 알람", "잠긴 폰에서 알람 화면을 바로 띄워요", r.fullScreenIntentAllowed,
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                    ReadinessRow(
                        Icons.Rounded.BatteryAlert, "배터리 최적화 제외", "자는 동안에도 깨우기를 바로 받아요", r.ignoringBatteryOptimizations,
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        )
                    }
                }
            }

            if (AppUpdater.isEnabled) {
                SectionCard("앱 업데이트") {
                    SettingsRow(
                        icon = Icons.Rounded.SystemUpdate,
                        title = "업데이트 확인",
                        supporting = when {
                            checkingUpdate -> "확인하는 중…"
                            updateStatus != null -> updateStatus
                            else -> "지금 버전 ${BuildConfig.VERSION_NAME}"
                        },
                        onClick = if (checkingUpdate) null else {
                            {
                                checkingUpdate = true
                                updateStatus = null
                                scope.launch {
                                    try {
                                        val found = AppUpdater.checkForUpdate()
                                        if (found == null) {
                                            updateStatus = "최신 버전이에요 (${BuildConfig.VERSION_NAME})"
                                        } else {
                                            availableUpdate = found
                                            updateStatus = "새 버전 ${found.versionName} 있어요"
                                        }
                                    } catch (e: Exception) {
                                        updateStatus = "확인하지 못했어요: ${e.message ?: "네트워크 오류"}"
                                    } finally {
                                        checkingUpdate = false
                                    }
                                }
                            }
                        },
                    )
                    SettingsRow(
                        icon = Icons.Rounded.Update,
                        title = "앱 열 때 자동으로 확인",
                        supporting = "새 버전이 있으면 알려줘요. 설치는 직접 확인한 뒤에만 해요",
                        trailing = {
                            Switch(checked = autoUpdateCheck, onCheckedChange = { value ->
                                autoUpdateCheck = value
                                scope.launch { settings.setAutoUpdateCheck(value) }
                            })
                        },
                    )
                }
            }
        }
    }

    if (renaming) {
        var draft by remember { mutableStateOf(myName ?: "") }
        var renameError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            title = { Text("이름 변경") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_DISPLAY_NAME_LENGTH) },
                    singleLine = true,
                    isError = renameError != null,
                    supportingText = { Text(renameError ?: "${draft.length}/$MAX_DISPLAY_NAME_LENGTH") },
                )
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

    availableUpdate?.let { info ->
        UpdateDialog(info = info, onDismiss = { availableUpdate = null })
    }

    if (confirmRotateKey) {
        AlertDialog(
            onDismissRequest = { confirmRotateKey = false },
            icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            title = { Text("API 키를 재발급할까요?") },
            text = { Text("이 기기에 저장된 키가 즉시 새 키로 바뀌고, 이전 키는 바로 무효화돼요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotateKey = false
                    scope.launch {
                        try {
                            val rotated = api.rotateApiKey()
                            tokenStore.save(tokenStore.userId, rotated.apiKey)
                            keyRotated = true
                            accountError = null
                        } catch (e: Exception) {
                            accountError = "키 재발급 실패: ${e.toUserMessage()}"
                        }
                    }
                }) { Text("재발급") }
            },
            dismissButton = { TextButton(onClick = { confirmRotateKey = false }) { Text("취소") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
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
private fun ReadinessRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    ok: Boolean,
    onFix: () -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        supporting = supporting,
        iconTint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        onClick = if (ok) null else onFix,
        trailing = {
            if (ok) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = "완료", tint = WakeTheme.status.success)
            } else {
                TextButton(onClick = onFix) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("설정")
                }
            }
        },
    )
}
