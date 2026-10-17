package app.wakelink.android.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A wake that came with a message or a picture, kept so it can be seen after the alarm. */
data class ReceivedWake(
    val triggerId: Long,
    val from: String,
    val message: String?,
    /** "custom:<id>:<sha256>" of the picture, for [app.wakelink.android.sounds.ImageCache]. */
    val image: String?,
    /** Base64 content key of an end-to-end encrypted [image]. */
    val imageKey: String?,
    val receivedAt: Long,
)

/**
 * The most recent [ReceivedWake]s, newest first. Device-protected like
 * [SettingsRepository], since the alarm that records them can ring before
 * the first unlock.
 */
class ReceivedWakes(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val all: Flow<List<ReceivedWake>> = observe { read(it) }

    /** Whether something arrived since the list was last opened. */
    val hasUnseen: Flow<Boolean> = observe { p ->
        (read(p).firstOrNull()?.receivedAt ?: 0) > p.getLong(KEY_SEEN_AT, 0)
    }

    suspend fun add(wake: ReceivedWake) = update { list ->
        (listOf(wake) + list.filterNot { it.triggerId == wake.triggerId }).take(MAX_ENTRIES)
    }

    suspend fun remove(triggerId: Long) = update { list -> list.filterNot { it.triggerId == triggerId } }

    suspend fun clear() = update { emptyList() }

    suspend fun markSeen() = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_SEEN_AT, System.currentTimeMillis()).commit()
    }

    private fun <T> observe(read: (SharedPreferences) -> T): Flow<T> = callbackFlow {
        trySend(read(prefs))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> trySend(read(p)) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private suspend fun update(change: (List<ReceivedWake>) -> List<ReceivedWake>) = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val array = JSONArray()
            change(read(prefs)).forEach { w ->
                array.put(
                    JSONObject()
                        .put("trigger_id", w.triggerId)
                        .put("from", w.from)
                        .putOpt("message", w.message)
                        .putOpt("image", w.image)
                        .putOpt("image_key", w.imageKey)
                        .put("received_at", w.receivedAt)
                )
            }
            prefs.edit().putString(KEY_ENTRIES, array.toString()).commit()
        }
    }

    private fun read(p: SharedPreferences): List<ReceivedWake> {
        val array = runCatching { JSONArray(p.getString(KEY_ENTRIES, null) ?: return emptyList()) }.getOrNull()
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            ReceivedWake(
                triggerId = o.optLong("trigger_id"),
                from = o.optString("from"),
                message = o.optString("message").ifEmpty { null },
                image = o.optString("image").ifEmpty { null },
                imageKey = o.optString("image_key").ifEmpty { null },
                receivedAt = o.optLong("received_at"),
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "wakelink_received_de"
        const val KEY_ENTRIES = "entries"
        const val KEY_SEEN_AT = "seen_at"
        const val MAX_ENTRIES = 30
        val LOCK = Any()
    }
}
