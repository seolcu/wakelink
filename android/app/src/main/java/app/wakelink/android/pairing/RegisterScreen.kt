package app.wakelink.android.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wakelink.android.data.TokenStore
import app.wakelink.android.fcm.currentFcmToken
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.RegisterRequest
import kotlinx.coroutines.launch

@Composable
fun RegisterScreen(onRegistered: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("WakeLink에서 쓸 이름을 입력하세요")
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            singleLine = true,
            modifier = Modifier.padding(top = 16.dp),
        )
        error?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
        Button(
            enabled = displayName.isNotBlank() && !busy,
            modifier = Modifier.padding(top = 24.dp),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val tokenStore = TokenStore(context)
                        val fcmToken = runCatching { currentFcmToken() }.getOrNull()
                        val response = ApiClient.create(tokenStore)
                            .register(RegisterRequest(displayName = displayName.trim(), fcmToken = fcmToken))
                        tokenStore.save(response.userId, response.apiKey)
                        onRegistered()
                    } catch (e: Exception) {
                        error = "등록 실패: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
        ) {
            Text("시작하기")
        }
    }
}
