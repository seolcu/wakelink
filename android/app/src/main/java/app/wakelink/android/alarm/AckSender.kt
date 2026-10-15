package app.wakelink.android.alarm

import android.content.Context
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.data.isUserUnlocked
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.orThrow
import retrofit2.HttpException

/**
 * Tells the server a wake actually rang. The API key lives in
 * credential-protected storage, so an alarm that rang before the first
 * unlock (or while offline) is queued and sent later by [flushPending].
 */
object AckSender {
    suspend fun ackOrQueue(context: Context, triggerId: Long) {
        if (triggerId < 0) return // local self-test alarm, nothing to ack
        if (!send(context, triggerId)) SettingsRepository(context).addPendingAck(triggerId)
    }

    /** Sends queued acks; run on unlock (BOOT_COMPLETED) and on app start. */
    suspend fun flushPending(context: Context) {
        if (!isUserUnlocked(context)) return
        val settings = SettingsRepository(context)
        val failed = settings.takePendingAcks().filterNot { send(context, it) }
        failed.forEach { settings.addPendingAck(it) }
    }

    private suspend fun send(context: Context, triggerId: Long): Boolean {
        if (!isUserUnlocked(context)) return false
        return try {
            val tokenStore = TokenStore(context)
            if (!tokenStore.isRegistered) return true // nothing we could ever ack with; drop it
            ApiClient.create(tokenStore).ackTrigger(triggerId).orThrow()
            true
        } catch (e: HttpException) {
            // 4xx: the server will never accept it (trigger/pairing gone); don't retry forever.
            e.code() in 400..499
        } catch (_: Exception) {
            false
        }
    }
}
