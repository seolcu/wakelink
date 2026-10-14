package app.wakelink.android.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.wakelink.android.R
import app.wakelink.android.data.TokenStore
import app.wakelink.android.fcm.currentFcmToken
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.RegisterRequest
import app.wakelink.android.network.toUserMessage
import app.wakelink.android.ui.components.SunriseMark
import kotlinx.coroutines.launch

/** Mirrors the server's maxDisplayNameLength (server/internal/api/register.go). */
const val MAX_DISPLAY_NAME_LENGTH = 40

@Composable
fun RegisterScreen(onRegistered: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var displayName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (displayName.isBlank() || busy) return
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
                error = context.getString(R.string.register_failed, e.toUserMessage(context))
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SunriseMark(size = 72.dp)
        Text("WakeLink", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.register_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it.take(MAX_DISPLAY_NAME_LENGTH) },
            singleLine = true,
            label = { Text(stringResource(R.string.register_name_label)) },
            supportingText = {
                Text(error ?: stringResource(R.string.register_name_hint, displayName.length, MAX_DISPLAY_NAME_LENGTH))
            },
            isError = error != null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = displayName.isNotBlank() && !busy,
            onClick = ::submit,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp).align(Alignment.CenterVertically))
            } else {
                Text(stringResource(R.string.register_start))
            }
        }
    }
}
