package app.wakelink.android.alarm

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.wakelink.android.data.DismissMode
import app.wakelink.android.data.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * The full-screen lock-screen ringing UI, launched via the alarm
 * notification's full-screen intent. Whether "stop" requires an actual
 * unlock first depends on the dismiss-mode setting (default:
 * unlock-required, like a normal alarm clock).
 */
class RingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val keyguardManager = getSystemService(KeyguardManager::class.java)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val fromDisplayName by AlarmForegroundService.ringingFrom.collectAsState()
                    // However the alarm ends -- this screen, the notification's
                    // Stop action, the auto-stop timeout -- this screen goes too.
                    // Also covers being relaunched from a stale notification
                    // after the process died.
                    LaunchedEffect(fromDisplayName) {
                        if (fromDisplayName == null) finish()
                    }
                    fromDisplayName?.let { name ->
                        RingingScreen(
                            fromDisplayName = name,
                            onStopRequested = { requireUnlockFirst ->
                                if (requireUnlockFirst && keyguardManager.isKeyguardLocked) {
                                    keyguardManager.requestDismissKeyguard(
                                        this,
                                        object : KeyguardManager.KeyguardDismissCallback() {
                                            override fun onDismissSucceeded() {
                                                stopAndFinish()
                                            }
                                            // onDismissCancelled() / onDismissError(): leave the
                                            // alarm ringing -- the whole point of unlock-required
                                            // mode is that it must not be defeatable by cancelling.
                                        },
                                    )
                                } else {
                                    stopAndFinish()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun stopAndFinish() {
        val stopIntent = Intent(this, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, stopIntent)
        finish()
    }
}

@Composable
private fun RingingScreen(
    fromDisplayName: String,
    onStopRequested: (requireUnlockFirst: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var dismissMode by remember { mutableStateOf(DismissMode.UNLOCK_REQUIRED) }

    LaunchedEffect(Unit) {
        dismissMode = SettingsRepository(context).dismissMode.first()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$fromDisplayName 님이 깨우고 있어요",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Button(
            modifier = Modifier.padding(top = 32.dp),
            onClick = { onStopRequested(dismissMode == DismissMode.UNLOCK_REQUIRED) },
        ) {
            Text(if (dismissMode == DismissMode.UNLOCK_REQUIRED) "잠금 해제하고 알람 끄기" else "알람 끄기")
        }
    }
}
