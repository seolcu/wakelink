package app.wakelink.android.inbox

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import app.wakelink.android.MainActivity
import app.wakelink.android.R
import app.wakelink.android.WakeLinkApplication
import app.wakelink.android.data.ReceivedWake
import app.wakelink.android.sounds.ImageCache
import app.wakelink.android.sounds.SoundRef
import app.wakelink.android.sounds.parseSoundRef

/**
 * After the alarm stops, the message or picture that came with a wake stays
 * in the notification shade (and in the received list), so it isn't gone
 * the moment the alarm screen closes.
 */
object WakeMessageNotifier {
    private const val TAG = "wake_message"

    fun post(context: Context, wake: ReceivedWake) {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_INBOX,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_INBOX, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val picture = (parseSoundRef(wake.image) as? SoundRef.Custom)
            ?.let { ImageCache.cached(context, it) }
            ?.let { file ->
                // Downsampled: a notification only needs a preview.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_EDGE) sample *= 2
                runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
            }
        val text = wake.message ?: context.getString(R.string.message_notification_picture)
        val builder = NotificationCompat.Builder(context, WakeLinkApplication.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_wakelink)
            .setContentTitle(context.getString(R.string.message_notification_title, wake.from))
            .setContentText(text)
            .setWhen(wake.receivedAt)
            .setShowWhen(true)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (picture != null) {
            builder.setLargeIcon(picture)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?).setSummaryText(wake.message))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(TAG, wake.triggerId.toInt(), builder.build())
        }
    }

    /** Once the received list is open, these notifications have done their job. */
    fun cancelAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching { nm.activeNotifications.filter { it.tag == TAG }.forEach { nm.cancel(TAG, it.id) } }
    }

    private const val REQUEST_OPEN_INBOX = 7
    private const val PREVIEW_EDGE = 1024
}
