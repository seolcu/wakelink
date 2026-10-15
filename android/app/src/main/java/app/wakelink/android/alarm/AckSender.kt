package app.wakelink.android.alarm

import android.content.Context
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.data.isUserUnlocked
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.orThrow
import retrofit2.HttpException

/**
 * Tells the server a wake actually rang, and later whether the user stopped
 * it themselves (the sender's "they're up"). The API key lives in
 * credential-protected storage, so anything from before the first unlock
 * (or while offline) is queued and sent later by [flushPending].
 */
object AckSender {
    suspend fun ackOrQueue(context: Context, triggerId: Long) {
        if (triggerId < 0) return // local self-test alarm, nothing to ack
        if (!send(context, triggerId)) SettingsRepository(context).addPendingAck(triggerId)
    }

    suspend fun dismissOrQueue(context: Context, triggerId: Long) {
        if (triggerId < 0) return
        if (!send(context, triggerId, dismiss = true)) SettingsRepository(context).addPendingDismiss(triggerId)
    }

    /** Sends queued acks and dismissals; run on unlock (BOOT_COMPLETED) and on app start. */
    suspend fun flushPending(context: Context) {
        if (!isUserUnlocked(context)) return
        val settings = SettingsRepository(context)
        settings.takePendingAcks().filterNot { send(context, it) }.forEach { settings.addPendingAck(it) }
        settings.takePendingDismisses().filterNot { send(context, it, dismiss = true) }.forEach { settings.addPendingDismiss(it) }
    }

    private suspend fun send(context: Context, triggerId: Long, dismiss: Boolean = false): Boolean {
        if (!isUserUnlocked(context)) return false
        return try {
            val tokenStore = TokenStore(context)
            if (!tokenStore.isRegistered) return true // nothing we could ever send it with; drop it
            val api = ApiClient.create(tokenStore)
            (if (dismiss) api.dismissTrigger(triggerId) else api.ackTrigger(triggerId)).orThrow()
            true
        } catch (e: HttpException) {
            // 4xx: the server will never accept it (trigger/pairing gone); don't retry forever.
            e.code() in 400..499
        } catch (_: Exception) {
            false
        }
    }
}
