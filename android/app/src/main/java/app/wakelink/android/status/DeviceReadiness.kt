package app.wakelink.android.status

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
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
    /**
     * Not background-restricted and not in the "restricted" standby bucket --
     * where Samsung's "sleeping apps" lists and Android's "Restricted"
     * battery setting put an app, blocking it from starting in the background.
     */
    val backgroundUnrestricted: Boolean,
    /** Needed only when the user turned on ringing through DND "Total silence". */
    val dndAccessGranted: Boolean,
) {
    /** Everything a wake needs to reliably reach this device. */
    val allReady: Boolean
        get() = notificationsEnabled && fullScreenIntentAllowed && ignoringBatteryOptimizations && backgroundUnrestricted

    companion object {
        fun check(context: Context): DeviceReadiness {
            val nm = context.getSystemService(NotificationManager::class.java)
            val pm = context.getSystemService(PowerManager::class.java)
            return DeviceReadiness(
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                fullScreenIntentAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                    nm.canUseFullScreenIntent(),
                ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(context.packageName),
                backgroundUnrestricted = isBackgroundUnrestricted(context),
                dndAccessGranted = nm.isNotificationPolicyAccessGranted,
            )
        }

        private fun isBackgroundUnrestricted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return true
            val restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
            val bucket = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
            return !restricted && bucket < STANDBY_BUCKET_RESTRICTED
        }

        /** UsageStatsManager.STANDBY_BUCKET_RESTRICTED (public since API 30; the bucket exists from 28 on). */
        private const val STANDBY_BUCKET_RESTRICTED = 45
    }
}
