package app.wakelink.android.status

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneLocked
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wakelink.android.R
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.ui.OnResume
import app.wakelink.android.ui.components.SunriseMark
import app.wakelink.android.ui.theme.WakeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Whether this device is a Samsung, whose extra battery "sleeping apps" lists can block wakes. */
private val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

/**
 * The "get ready" checklist: every system setting a wake depends on, each
 * fixable with one tap. Shown right after registering and on any launch
 * where something is missing, so a wake is never lost to a permission
 * nobody noticed was off. Re-checks whenever the user comes back from the
 * system Settings app.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var readiness by remember { mutableStateOf(DeviceReadiness.check(context)) }

    OnResume { readiness = DeviceReadiness.check(context) }

    fun refresh() {
        readiness = DeviceReadiness.check(context)
        scope.launch {
            ArmedStatusNotifier.update(context, SettingsRepository(context).armed.first())
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) refresh()
        else context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
    ) {
        SunriseMark(size = 64.dp, sun = MaterialTheme.colorScheme.secondary)
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.setup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SetupItem(
            icon = Icons.Rounded.NotificationsActive,
            title = stringResource(R.string.settings_ready_notifications),
            body = stringResource(R.string.setup_notifications_body),
            done = readiness.notificationsEnabled,
            onFix = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            SetupItem(
                icon = Icons.Rounded.PhoneLocked,
                title = stringResource(R.string.settings_ready_full_screen),
                body = stringResource(R.string.setup_full_screen_body),
                done = readiness.fullScreenIntentAllowed,
                onFix = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                    )
                },
            )
        }
        SetupItem(
            icon = Icons.Rounded.BatteryAlert,
            title = stringResource(R.string.settings_ready_battery),
            body = stringResource(R.string.setup_battery_body),
            done = readiness.ignoringBatteryOptimizations,
            onFix = {
                @Suppress("BatteryLife") // waking someone on time is this app's whole purpose
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            },
        )
        if (isSamsung) {
            // Samsung's "sleeping apps" lists can't be read by apps, so this
            // one is a pointer to the right screen rather than a checkmark.
            SetupItem(
                icon = Icons.Rounded.Bedtime,
                title = stringResource(R.string.setup_samsung_title),
                body = stringResource(R.string.setup_samsung_body),
                done = null,
                onFix = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                },
            )
        }

        if (readiness.allReady) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.setup_done))
            }
        } else {
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.setup_later))
            }
        }
    }
}

/** One checklist row; [done] null means "can't be checked, just a pointer". */
@Composable
private fun SetupItem(icon: ImageVector, title: String, body: String, done: Boolean?, onFix: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                if (done == true) Icons.Rounded.CheckCircle else icon,
                contentDescription = null,
                tint = if (done == true) WakeTheme.status.success else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (done != true) {
                FilledTonalButton(onClick = onFix) {
                    Text(stringResource(if (done == null) R.string.setup_open else R.string.setup_fix))
                }
            }
        }
    }
}
