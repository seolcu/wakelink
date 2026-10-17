package app.wakelink.android.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.wakelink.android.WakeLinkApplication
import app.wakelink.android.alarm.AlarmForegroundService
import app.wakelink.android.network.AcceptingRequest
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.orThrow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The "receiving wake-ups" switch lives on the phone (the alarm path reads
 * it even before unlock) and is mirrored to the server, so contacts see it
 * and wakes are refused up front while it's off.
 *
 * Switching off can be a pause with an end time, because a switch left off
 * by mistake silently defeats the whole app. The end time is kept in three
 * places so it holds even if one fails: [SettingsRepository.armed] treats
 * an ended pause as on, the server does the same, and an alarm
 * ([ArmedResumeReceiver]) switches it back on for real.
 */
object ArmedSync {
    /** [resumeAt] (epoch millis) is when a pause ends; null = until switched back on. */
    suspend fun set(context: Context, armed: Boolean, resumeAt: Long? = null) {
        val until = resumeAt.takeIf { !armed }
        SettingsRepository(context).setArmed(armed, until)
        ArmedResumeReceiver.schedule(context, until)
        // Switching off also silences a wake that's ringing right now.
        if (!armed && AlarmForegroundService.ringingFrom.value != null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmForegroundService::class.java).setAction(AlarmForegroundService.ACTION_STOP),
            )
        }
        push(context, armed, until)
    }

    /**
     * Switches back on if a pause has ended, otherwise makes sure its alarm
     * is set (alarms don't survive a reboot). Safe to run before unlock.
     */
    suspend fun resumeIfDue(context: Context) {
        val settings = SettingsRepository(context)
        val resumeAt = settings.armedResumeAt.first() ?: return
        if (System.currentTimeMillis() >= resumeAt) set(context, true) else ArmedResumeReceiver.schedule(context, resumeAt)
    }

    /** Re-sends the current value, e.g. after it was changed offline. Run on unlocked starts. */
    suspend fun syncCurrent(context: Context) {
        resumeIfDue(context)
        val settings = SettingsRepository(context)
        val resumeAt = settings.armedResumeAt.first()
        push(context, armed = resumeAt == null && settings.armed.first(), resumeAt)
    }

    private suspend fun push(context: Context, armed: Boolean, resumeAt: Long?) {
        if (!isUserUnlocked(context)) return
        val tokenStore = TokenStore(context)
        if (!tokenStore.isRegistered) return
        val resumeAtSeconds = resumeAt?.let { (it + 999) / 1000 }
        runCatching { ApiClient.create(tokenStore).putAccepting(AcceptingRequest(armed, resumeAtSeconds)).orThrow() }
    }
}

/** Ends a pause of the "receiving wake-ups" switch when its time comes. */
class ArmedResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        (context.applicationContext as WakeLinkApplication).appScope.launch {
            try {
                ArmedSync.resumeIfDue(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /**
         * Sets (or with null, cancels) the alarm. Inexact is fine: until it
         * fires, [SettingsRepository.armed] and the server already count an
         * ended pause as on.
         */
        fun schedule(context: Context, atMillis: Long?) {
            val pending = PendingIntent.getBroadcast(
                context, 0, Intent(context, ArmedResumeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (atMillis == null) alarms.cancel(pending) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }
}
