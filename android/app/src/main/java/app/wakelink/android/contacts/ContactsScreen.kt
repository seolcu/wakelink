package app.wakelink.android.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.PairingView
import app.wakelink.android.network.PatchPairingRequest
import app.wakelink.android.network.TriggerView
import app.wakelink.android.network.WakeLinkApi
import app.wakelink.android.network.orThrow
import app.wakelink.android.network.toUserMessage
import app.wakelink.android.status.DeviceReadiness
import app.wakelink.android.ui.OnResume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val ACK_POLL_INTERVAL_MS = 2_000L
private const val ACK_POLL_ATTEMPTS = 30 // ~60s: long enough for a dozing phone to wake on a high-priority push

/**
 * Polls the trigger list until the receiver acks [triggerId], FCM reports
 * failure, or we give up. Transient network errors while polling don't end
 * the wait -- the push may well have gone through.
 */
private suspend fun awaitAck(api: WakeLinkApi, pairingId: Long, triggerId: Long): AckCheck {
    repeat(ACK_POLL_ATTEMPTS) {
        delay(ACK_POLL_INTERVAL_MS)
        val triggers = runCatching { api.listTriggers(pairingId, limit = 10) }.getOrNull() ?: return@repeat
        val check = ackCheckFor(triggerId, triggers)
        if (check != AckCheck.PENDING) return check
    }
    return AckCheck.PENDING
}

@Composable
fun ContactsScreen(onOpenPairing: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { TokenStore(context) }
    val api = remember { ApiClient.create(tokenStore) }

    var pairings by remember { mutableStateOf<List<PairingView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val warnings = remember { mutableStateListOf<String>() }

    suspend fun reload() {
        loading = true
        try {
            pairings = api.listPairings()
            loadError = null
        } catch (e: Exception) {
            loadError = "목록을 불러오지 못했어요: ${e.toUserMessage()}"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    OnResume {
        scope.launch {
            val readiness = DeviceReadiness.check(context)
            val armed = SettingsRepository(context).armed.first()
            warnings.clear()
            if (!armed) warnings += "WakeLink가 비활성화되어 있어 아무도 나를 깨울 수 없어요"
            if (!readiness.notificationsEnabled) warnings += "알림이 꺼져 있어 깨우기 알람이 제대로 표시되지 않을 수 있어요"
            if (!readiness.fullScreenIntentAllowed) warnings += "전체 화면 알림 권한이 없어 잠금 화면에 알람 화면이 뜨지 않아요"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WakeLink", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { reload() } }) { Text("새로고침") }
            TextButton(onClick = onOpenSettings) { Text("설정") }
        }
        Button(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("+ 연락처 추가")
        }

        if (warnings.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).clickable(onClick = onOpenSettings),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer) }
                    Text(
                        "탭해서 설정 열기",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        loadError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        }

        when {
            loading && pairings.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            pairings.isEmpty() && loadError == null -> Text(
                "아직 연결된 사람이 없어요.\n'+ 연락처 추가'에서 초대 코드를 주고받으면 서로 깨울 수 있어요.",
                modifier = Modifier.padding(top = 32.dp),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pairings, key = { it.id }) { pairing ->
                    PairingCard(
                        pairing = pairing,
                        api = api,
                        myUserId = tokenStore.userId,
                        onChanged = { scope.launch { reload() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingCard(pairing: PairingView, api: WakeLinkApi, myUserId: Long, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var wakeStatus by remember { mutableStateOf<String?>(null) }
    var waking by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<TriggerView>?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var toggling by remember { mutableStateOf(false) }

    suspend fun loadHistory() {
        history = runCatching { api.listTriggers(pairing.id, limit = 10) }.getOrNull()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(pairing.otherUserName, style = MaterialTheme.typography.titleMedium)
            Text(
                "이 사람이 나를 깨울 수 있음: ${if (pairing.theyCanWakeMe) "허용" else "꺼짐 (상대가 껐어요)"}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("내가 이 사람을 깨울 수 있음", modifier = Modifier.weight(1f))
                Switch(
                    checked = pairing.iCanWakeThem,
                    enabled = !toggling,
                    onCheckedChange = { allowed ->
                        toggling = true
                        scope.launch {
                            try {
                                api.patchPairing(pairing.id, PatchPairingRequest(iCanWakeThem = allowed)).orThrow()
                            } catch (e: Exception) {
                                wakeStatus = "설정 변경 실패: ${e.toUserMessage()}"
                            }
                            toggling = false
                            onChanged()
                        }
                    },
                )
            }

            Button(
                enabled = pairing.iCanWakeThem && !waking,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    waking = true
                    wakeStatus = "보내는 중…"
                    scope.launch {
                        try {
                            val triggerId = try {
                                api.trigger(pairing.id).triggerId
                            } catch (e: Exception) {
                                wakeStatus = "전송 실패: ${e.toUserMessage()}"
                                return@launch
                            }
                            // Sent is not rang: keep the user waiting on the
                            // receiver's ack before claiming success.
                            wakeStatus = "전송됨 — ${pairing.otherUserName}님 폰에서 울리는지 확인 중…"
                            wakeStatus = when (awaitAck(api, pairing.id, triggerId)) {
                                AckCheck.ACKED -> "${pairing.otherUserName}님 폰에서 알람이 울리고 있어요 ✓"
                                AckCheck.FAILED -> "푸시 전달에 실패했어요"
                                AckCheck.PENDING -> "전송은 됐지만 울렸는지 확인되지 않았어요 (상대 폰이 꺼져 있거나 오프라인일 수 있어요)"
                            }
                            if (showHistory) loadHistory()
                        } finally {
                            waking = false
                        }
                    }
                },
            ) {
                Text("깨우기")
            }
            wakeStatus?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                TextButton(onClick = {
                    showHistory = !showHistory
                    if (showHistory) scope.launch { loadHistory() }
                }) {
                    Text(if (showHistory) "기록 숨기기" else "최근 기록")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { confirmUnpair = true }) {
                    Text("연결 끊기", color = MaterialTheme.colorScheme.error)
                }
            }

            if (showHistory) {
                when (val h = history) {
                    null -> Text("기록을 불러오는 중…", style = MaterialTheme.typography.bodySmall)
                    else -> if (h.isEmpty()) {
                        Text("아직 기록이 없어요", style = MaterialTheme.typography.bodySmall)
                    } else {
                        h.forEach { Text(historyLine(it, myUserId), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("${pairing.otherUserName}님과 연결 끊기") },
            text = { Text("서로 더 이상 깨울 수 없게 되고 기록도 삭제돼요. 다시 연결하려면 초대 코드를 새로 주고받아야 해요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch {
                        try {
                            api.deletePairing(pairing.id).orThrow()
                        } catch (e: Exception) {
                            wakeStatus = "연결 끊기 실패: ${e.toUserMessage()}"
                        }
                        onChanged()
                    }
                }) { Text("연결 끊기", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("취소") } },
        )
    }
}
