package app.wakelink.android.status

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * The system-level settings that decide whether a wake actually reaches
 * the user. Each one can be silently revoked in the Settings app, so the
 * UI re-checks these on every resume rather than once.
 */
data class DeviceReadiness(
    val notificationsEnabled: Boolean,
    /** Android 14+ lets users (and Play policy) revoke full-screen intents. */
    val fullScreenIntentAllowed: Boolean,
    val ignoringBatteryOptimizations: Boolean,
) {
    /** Everything a wake needs to reliably reach this device. */
    val allReady: Boolean get() = notificationsEnabled && fullScreenIntentAllowed && ignoringBatteryOptimizations

    companion object {
        fun check(context: Context): DeviceReadiness {
            val nm = context.getSystemService(NotificationManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)
            return DeviceReadiness(
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                fullScreenIntentAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    nm.canUseFullScreenIntent(),
                ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
            )
        }
    }
}
