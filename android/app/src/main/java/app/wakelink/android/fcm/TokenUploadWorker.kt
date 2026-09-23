package app.wakelink.android.fcm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import app.wakelink.android.data.TokenStore
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.UpdateTokenRequest

/**
 * Uploads a refreshed FCM token to the backend. Runs as a retrying
 * WorkManager job -- onNewToken can fire with nothing else active, and a
 * dropped upload means a stale token, which means every future trigger
 * silently fails to deliver.
 */
class TokenUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val token = inputData.getString(KEY_TOKEN) ?: return Result.failure()
        val tokenStore = TokenStore(applicationContext)
        if (!tokenStore.isRegistered) {
            // Not registered/paired yet -- the token will be sent as part
            // of the register call instead once the user completes setup.
            return Result.success()
        }
        return try {
            ApiClient.create(tokenStore).updateToken(UpdateTokenRequest(fcmToken = token))
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEY_TOKEN = "token"
        fun inputData(token: String): Data = Data.Builder().putString(KEY_TOKEN, token).build()
    }
}
