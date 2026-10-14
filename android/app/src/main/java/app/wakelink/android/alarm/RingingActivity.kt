package app.wakelink.android.alarm

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.wakelink.android.R
import app.wakelink.android.data.DismissMode
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.ui.theme.Dawn
import app.wakelink.android.ui.theme.Night
import app.wakelink.android.ui.theme.NightDeep
import app.wakelink.android.ui.theme.Sunrise
import app.wakelink.android.ui.theme.WakeLinkTheme
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The full-screen lock-screen ringing UI, launched via the alarm
 * notification's full-screen intent. Whether "stop" requires an actual
 * unlock first depends on the dismiss-mode setting (default:
 * unlock-required, like a normal alarm clock).
 */
class RingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Always a dark night-sky screen, so always light bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
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
            WakeLinkTheme(darkTheme = true) {
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

    private fun stopAndFinish() {
        val stopIntent = Intent(this, AlarmForegroundService::class.java).apply {
            action = AlarmForegroundService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, stopIntent)
        finish()
    }
}

private val clockFormat = DateTimeFormatter.ofPattern("H:mm")

@Composable
private fun RingingScreen(
    fromDisplayName: String,
    onStopRequested: (requireUnlockFirst: Boolean) -> Unit,
) {
    val context = LocalContext.current
    var dismissMode by remember { mutableStateOf(DismissMode.UNLOCK_REQUIRED) }
    var now by remember { mutableStateOf(LocalTime.now()) }

    LaunchedEffect(Unit) {
        dismissMode = SettingsRepository(context).dismissMode.first()
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }

    // A slow "breathing" sun. Compose scales this with the system
    // animator-duration setting, so "remove animations" stops it.
    val pulse = rememberInfiniteTransition(label = "sun")
    val glow by pulse.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to NightDeep, 0.55f to Night, 1f to Color(0xFF8A4A2A))),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                now.format(clockFormat),
                color = Color.White,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 92.sp),
            )

            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f).fillMaxWidth()) {
                Canvas(modifier = Modifier.size(240.dp)) {
                    val r = size.minDimension / 2
                    drawCircle(Sunrise.copy(alpha = 0.18f * glow), radius = r * glow)
                    drawCircle(Sunrise.copy(alpha = 0.32f * glow), radius = r * 0.72f * glow)
                    drawCircle(Brush.radialGradient(listOf(Dawn, Sunrise), radius = r * 0.5f), radius = r * 0.46f)
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    fromDisplayName,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.ringing_subtitle),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(40.dp))
            val unlockFirst = dismissMode == DismissMode.UNLOCK_REQUIRED
            Button(
                onClick = { onStopRequested(unlockFirst) },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = NightDeep),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Icon(if (unlockFirst) Icons.Rounded.LockOpen else Icons.Rounded.AlarmOff, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text(stringResource(if (unlockFirst) R.string.ringing_unlock_stop else R.string.ringing_stop), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
