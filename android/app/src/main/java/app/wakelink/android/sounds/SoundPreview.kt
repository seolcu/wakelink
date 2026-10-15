package app.wakelink.android.sounds

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt

/**
 * Short in-app previews of alarm sounds, played the way a real wake-up
 * would be: on the alarm stream, optionally at WakeLink's own volume (the
 * device's alarm volume is put back afterwards). One preview at a time.
 */
object SoundPreview {
    private const val PREVIEW_MS = 3_000L

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var priorVolume: Int? = null
    private var audioManager: AudioManager? = null
    private val stopRunnable = Runnable { stop() }

    /** Plays [ref] for a few seconds; [volumePercent] null keeps the device's alarm volume. */
    fun play(context: Context, ref: SoundRef, volumePercent: Int? = null) {
        val uri = when (ref) {
            SoundRef.Device -> RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            is SoundRef.Preset -> presetSound(ref.name)?.let { presetUri(context, it) }
            is SoundRef.Custom -> SoundCache.cached(context, ref)?.let { Uri.fromFile(it) }
        } ?: return
        playUri(context, uri, volumePercent)
    }

    fun playUri(context: Context, uri: Uri, volumePercent: Int? = null, loop: Boolean = true, durationMs: Long = PREVIEW_MS) {
        stop()
        val am = context.getSystemService(AudioManager::class.java)
        if (volumePercent != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            priorVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            audioManager = am
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * volumePercent / 100.0).roundToInt().coerceIn(1, max), 0)
        }
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.setDataSource(context.applicationContext, uri)
            p.isLooping = loop
            if (!loop) p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p
            handler.postDelayed(stopRunnable, durationMs)
        } catch (_: Exception) {
            p.release()
            stop()
        }
    }

    fun stop() {
        handler.removeCallbacks(stopRunnable)
        player?.apply {
            runCatching { stop() }
            release()
        }
        player = null
        priorVolume?.let { audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        priorVolume = null
        audioManager = null
    }
}
