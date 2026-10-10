package app.wakelink.android.data

import android.app.NotificationManager
import android.content.Context

/**
 * Self-healing guard: if the process died mid-ring with a DND override
 * still applied, put the interruption filter back to what the user had
 * chosen, rather than leaving the phone stuck on a DND state it didn't
 * choose. Run on boot and on every app process start.
 */
suspend fun restorePendingDndOverride(context: Context) {
    val settings = SettingsRepository(context)
    if (!settings.isDndOverrideActive()) return
    val priorFilter = settings.consumePendingDndOverride() ?: return
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.isNotificationPolicyAccessGranted) {
        nm.setInterruptionFilter(priorFilter)
    }
}
