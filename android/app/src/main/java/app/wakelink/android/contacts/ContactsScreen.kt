package app.wakelink.android.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import app.wakelink.android.data.TokenStore
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.PairingView
import app.wakelink.android.network.PatchPairingRequest
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(onOpenPairing: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { ApiClient.create(TokenStore(context)) }

    var pairings by remember { mutableStateOf<List<PairingView>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        pairings = runCatching { api.listPairings() }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onOpenPairing) { Text("+ 연락처 추가") }
            TextButton(onClick = onOpenSettings) { Text("설정") }
        }

        status?.let { Text(it, modifier = Modifier.padding(vertical = 8.dp)) }

        LazyColumn {
            items(pairings, key = { it.id }) { pairing ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text(pairing.otherUserName)
                    Text("이 사람이 나를 깨울 수 있음: ${if (pairing.theyCanWakeMe) "허용" else "차단"}")

                    Row {
                        Text("내가 이 사람을 깨울 수 있음")
                        Switch(
                            checked = pairing.iCanWakeThem,
                            onCheckedChange = { allowed ->
                                scope.launch {
                                    runCatching { api.patchPairing(pairing.id, PatchPairingRequest(iCanWakeThem = allowed)) }
                                    reload()
                                }
                            },
                        )
                    }

                    Button(
                        enabled = pairing.iCanWakeThem,
                        onClick = {
                            scope.launch {
                                status = null
                                try {
                                    api.trigger(pairing.id)
                                    status = "${pairing.otherUserName}님에게 전송됨"
                                } catch (e: Exception) {
                                    status = "전송 실패: ${e.message}"
                                }
                            }
                        },
                    ) {
                        Text("깨우기")
                    }
                }
            }
        }
    }
}
