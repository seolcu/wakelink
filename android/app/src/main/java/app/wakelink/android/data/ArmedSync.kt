package app.wakelink.android.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.wakelink.android.alarm.AlarmForegroundService
import app.wakelink.android.network.AcceptingRequest
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.orThrow
import kotlinx.coroutines.flow.first

/**
 * The "receiving wake-ups" switch lives on the phone (the alarm path reads
 * it even before unlock) and is mirrored to the server, so contacts see it
 * and wakes are refused up front while it's off.
 */
object ArmedSync {
    suspend fun set(context: Context, armed: Boolean) {
        SettingsRepository(context).setArmed(armed)
        // Switching off also silences a wake that's ringing right now.
        if (!armed && AlarmForegroundService.ringingFrom.value != null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmForegroundService::class.java).setAction(AlarmForegroundService.ACTION_STOP),
            )
        }
        push(context, armed)
    }

    /** Re-sends the current value, e.g. after it was changed offline. Run on unlocked starts. */
    suspend fun syncCurrent(context: Context) {
        push(context, SettingsRepository(context).armed.first())
    }

    private suspend fun push(context: Context, armed: Boolean) {
        if (!isUserUnlocked(context)) return
        val tokenStore = TokenStore(context)
        if (!tokenStore.isRegistered) return
        runCatching { ApiClient.create(tokenStore).putAccepting(AcceptingRequest(armed)).orThrow() }
    }
}
