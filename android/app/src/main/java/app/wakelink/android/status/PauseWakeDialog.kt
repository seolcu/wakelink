package app.wakelink.android.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wakelink.android.R
import app.wakelink.android.data.ArmedSync
import app.wakelink.android.data.PAUSE_CHOICES_HOURS
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.resumeLabel
import kotlinx.coroutines.delay

/** The "receiving wake-ups" switch as the UI shows it. */
data class ArmedState(val armed: Boolean, val resumeAt: Long?)

/**
 * Observes the switch, and while this screen is shown switches back on the
 * moment a pause ends (the background alarm for it may run a little late).
 */
@Composable
fun rememberArmedState(): ArmedState {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val armed by settings.armed.collectAsState(initial = true)
    val resumeAt by settings.armedResumeAt.collectAsState(initial = null)
    LaunchedEffect(resumeAt) {
        val at = resumeAt ?: return@LaunchedEffect
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        ArmedSync.resumeIfDue(context)
    }
    return ArmedState(armed, resumeAt)
}

/** The line under the switch: who can wake you, or when it comes back on. */
@Composable
fun armedSupportingText(state: ArmedState): String = when {
    state.armed -> stringResource(R.string.settings_armed_on)
    state.resumeAt != null -> resumeLabel(state.resumeAt, System.currentTimeMillis()).resolve(LocalContext.current)
    else -> stringResource(R.string.settings_armed_off)
}

/** Asked when switching wake-ups off: for how long. [onPause] gets the end time (epoch millis) or null. */
@Composable
fun PauseWakeDialog(onPause: (resumeAt: Long?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.armed_pause_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.armed_pause_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                PAUSE_CHOICES_HOURS.forEach { hours ->
                    Text(
                        if (hours != null) pluralStringResource(R.plurals.armed_pause_hours, hours, hours)
                        else stringResource(R.string.armed_pause_forever),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPause(hours?.let { System.currentTimeMillis() + it * 3_600_000L }) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
