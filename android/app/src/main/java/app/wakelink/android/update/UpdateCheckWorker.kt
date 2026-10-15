package app.wakelink.android.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.wakelink.android.MainActivity
import app.wakelink.android.R
import app.wakelink.android.WakeLinkApplication
import app.wakelink.android.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Checks for a new release in the background and posts a notification,
 * once per version -- people who mostly just receive wakes rarely open the
 * app, so the startup check alone would leave them on old versions.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        if (!AppUpdater.isEnabled || !settings.autoUpdateCheck.first()) return Result.success()
        val update = try {
            AppUpdater.checkForUpdate()
        } catch (_: Exception) {
            return Result.retry()
        } ?: return Result.success()
        if (update.versionName == settings.notifiedUpdateVersion.first()) return Result.success()

        notify(applicationContext, update.versionName)
        settings.setNotifiedUpdateVersion(update.versionName)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "update-check"
        private const val NOTIFICATION_ID = 44

        /** Idempotent; called on every unlocked app start. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun notify(context: Context, versionName: String) {
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, WakeLinkApplication.CHANNEL_UPDATES)
                .setSmallIcon(R.drawable.ic_stat_wakelink)
                .setContentTitle(context.getString(R.string.update_notification_title, versionName))
                .setContentText(context.getString(R.string.update_notification_body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
        }
    }
}
