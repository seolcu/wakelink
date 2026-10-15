package app.wakelink.android.data

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Where settings lived before 0.3.0; read once by [SettingsRepository.migrateLegacyIfNeeded]. */
private val Context.legacyDataStore by preferencesDataStore(name = "wakelink_settings")

enum class DismissMode { UNLOCK_REQUIRED, TAP_TO_DISMISS }

/** App appearance; SYSTEM follows the phone's light/dark setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Whose choice of sound wins when someone wakes this device. */
enum class SoundPolicy {
    /** Play whatever the sender picked: a built-in sound or their own recording/file. */
    ALLOW_ALL,

    /** Senders may pick among the built-in sounds, but not their own recordings/files. */
    PRESETS_ONLY,

    /** Always play this device's own default sound. */
    MINE_ONLY,
}

/** True once the user has unlocked the device since boot (credential-encrypted storage is readable). */
fun isUserUnlocked(context: Context): Boolean =
    context.getSystemService(UserManager::class.java).isUserUnlocked

/**
 * App settings, plus the bits of durable state the alarm path needs.
 *
 * Everything lives in *device-protected* storage, so it can be read before
 * the user unlocks the phone after a reboot (Direct Boot): a wake push that
 * arrives then must still know whether the app is armed, how loud to ring,
 * which sound to play, and so on. Nothing here is sensitive -- the API key
 * stays in credential-protected [TokenStore].
 *
 * It also holds the DND self-healing state (see AlarmForegroundService /
 * [restorePendingDndOverride]) and acks owed for alarms that rang while the
 * device was still locked (see [addPendingAck]).
 */
class SettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private object Keys {
        const val ARMED = "armed"
        const val DND_BYPASS_ENABLED = "dnd_bypass_enabled"
        const val DISMISS_MODE = "dismiss_mode"
        const val VOLUME_PERCENT = "volume_percent"
        const val CRESCENDO = "crescendo"
        const val DEFAULT_SOUND = "default_sound"
        const val SOUND_POLICY = "sound_policy"
        const val DND_OVERRIDE_ACTIVE = "dnd_override_active"
        const val DND_PRIOR_FILTER = "dnd_prior_filter"
        const val AUTO_UPDATE_CHECK = "auto_update_check"
        const val SKIPPED_UPDATE_VERSION = "skipped_update_version"
        const val PENDING_ACKS = "pending_acks"
        const val SOUND_FOR_PAIRING_PREFIX = "sound_for_pairing_"
        const val IMAGE_FOR_PAIRING_PREFIX = "image_for_pairing_"
        const val SHOW_IMAGES = "show_images"
        const val THEME_MODE = "theme_mode"
        const val NOTIFIED_UPDATE_VERSION = "notified_update_version"
        const val LEGACY_MIGRATED = "legacy_migrated"
    }

    private fun <T> observe(read: (SharedPreferences) -> T): Flow<T> = callbackFlow {
        trySend(read(prefs))
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, _ -> trySend(read(p)) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private suspend fun edit(block: SharedPreferences.Editor.() -> Unit) = withContext(Dispatchers.IO) {
        prefs.edit().apply(block).commit()
    }

    val armed: Flow<Boolean> = observe { it.getBoolean(Keys.ARMED, true) }
    val dndBypassEnabled: Flow<Boolean> = observe { it.getBoolean(Keys.DND_BYPASS_ENABLED, false) }
    val dismissMode: Flow<DismissMode> = observe { readDismissMode(it) }

    /** WakeLink's alarm loudness as a percentage of the device's max alarm volume. */
    val volumePercent: Flow<Int> = observe { it.getInt(Keys.VOLUME_PERCENT, DEFAULT_VOLUME_PERCENT) }

    /** Start quiet and ramp up to [volumePercent] over the first ~30 seconds. */
    val crescendo: Flow<Boolean> = observe { it.getBoolean(Keys.CRESCENDO, false) }

    /** This device's own sound: a [app.wakelink.android.sounds.SoundRef] key ("device", "preset:chime"). */
    val defaultSound: Flow<String> = observe { it.getString(Keys.DEFAULT_SOUND, null) ?: DEFAULT_SOUND_KEY }
    val soundPolicy: Flow<SoundPolicy> = observe { readSoundPolicy(it) }

    /** Show pictures that senders attach to wake-ups on the alarm screen. */
    val showImages: Flow<Boolean> = observe { it.getBoolean(Keys.SHOW_IMAGES, true) }

    val themeMode: Flow<ThemeMode> = observe { p ->
        ThemeMode.entries.firstOrNull { it.name == p.getString(Keys.THEME_MODE, null) } ?: ThemeMode.SYSTEM
    }

    /** The newest version a "new version available" notification was already posted for. */
    val notifiedUpdateVersion: Flow<String?> = observe { it.getString(Keys.NOTIFIED_UPDATE_VERSION, null) }

    val autoUpdateCheck: Flow<Boolean> = observe { it.getBoolean(Keys.AUTO_UPDATE_CHECK, true) }

    /** Version the user chose "skip" for in the startup update prompt; manual checks ignore it. */
    val skippedUpdateVersion: Flow<String?> = observe { it.getString(Keys.SKIPPED_UPDATE_VERSION, null) }

    suspend fun setArmed(value: Boolean) = edit { putBoolean(Keys.ARMED, value) }
    suspend fun setDndBypassEnabled(value: Boolean) = edit { putBoolean(Keys.DND_BYPASS_ENABLED, value) }
    suspend fun setDismissMode(value: DismissMode) = edit { putString(Keys.DISMISS_MODE, value.name) }
    suspend fun setVolumePercent(value: Int) =
        edit { putInt(Keys.VOLUME_PERCENT, value.coerceIn(MIN_VOLUME_PERCENT, 100)) }
    suspend fun setCrescendo(value: Boolean) = edit { putBoolean(Keys.CRESCENDO, value) }
    suspend fun setDefaultSound(key: String) = edit { putString(Keys.DEFAULT_SOUND, key) }
    suspend fun setSoundPolicy(value: SoundPolicy) = edit { putString(Keys.SOUND_POLICY, value.name) }
    suspend fun setShowImages(value: Boolean) = edit { putBoolean(Keys.SHOW_IMAGES, value) }
    suspend fun setThemeMode(value: ThemeMode) = edit { putString(Keys.THEME_MODE, value.name) }
    suspend fun setNotifiedUpdateVersion(value: String) = edit { putString(Keys.NOTIFIED_UPDATE_VERSION, value) }
    suspend fun setAutoUpdateCheck(value: Boolean) = edit { putBoolean(Keys.AUTO_UPDATE_CHECK, value) }
    suspend fun setSkippedUpdateVersion(value: String) = edit { putString(Keys.SKIPPED_UPDATE_VERSION, value) }

    /** The sound this user last picked for waking the contact in [pairingId] (sender side). */
    fun soundForPairing(pairingId: Long): Flow<String?> =
        observe { it.getString(Keys.SOUND_FOR_PAIRING_PREFIX + pairingId, null) }

    suspend fun setSoundForPairing(pairingId: Long, key: String?) = edit {
        if (key == null) remove(Keys.SOUND_FOR_PAIRING_PREFIX + pairingId)
        else putString(Keys.SOUND_FOR_PAIRING_PREFIX + pairingId, key)
    }

    /** The picture (a "custom:<id>" key) this user attaches when waking [pairingId]; null = none. */
    fun imageForPairing(pairingId: Long): Flow<String?> =
        observe { it.getString(Keys.IMAGE_FOR_PAIRING_PREFIX + pairingId, null) }

    suspend fun setImageForPairing(pairingId: Long, key: String?) = edit {
        if (key == null) remove(Keys.IMAGE_FOR_PAIRING_PREFIX + pairingId)
        else putString(Keys.IMAGE_FOR_PAIRING_PREFIX + pairingId, key)
    }

    suspend fun isDndOverrideActive(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(Keys.DND_OVERRIDE_ACTIVE, false)
    }

    suspend fun savePendingDndOverride(priorFilter: Int) = edit {
        putBoolean(Keys.DND_OVERRIDE_ACTIVE, true)
        putInt(Keys.DND_PRIOR_FILTER, priorFilter)
    }

    /** Clears the pending-override flag and returns the filter to restore to, if any. */
    suspend fun consumePendingDndOverride(): Int? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val filter = if (prefs.contains(Keys.DND_PRIOR_FILTER)) prefs.getInt(Keys.DND_PRIOR_FILTER, 0) else null
            prefs.edit().putBoolean(Keys.DND_OVERRIDE_ACTIVE, false).remove(Keys.DND_PRIOR_FILTER).commit()
            filter
        }
    }

    /**
     * Remembers that [triggerId] rang but couldn't be acked yet (the API key
     * is unreadable before the first unlock); [takePendingAcks] hands them to
     * whoever can send them later.
     */
    suspend fun addPendingAck(triggerId: Long) = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val current = prefs.getStringSet(Keys.PENDING_ACKS, emptySet()).orEmpty()
            prefs.edit().putStringSet(Keys.PENDING_ACKS, current + triggerId.toString()).commit()
        }
    }

    suspend fun takePendingAcks(): List<Long> = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val current = prefs.getStringSet(Keys.PENDING_ACKS, emptySet()).orEmpty()
            prefs.edit().remove(Keys.PENDING_ACKS).commit()
            current.mapNotNull { it.toLongOrNull() }
        }
    }

    /**
     * One-time copy of the pre-0.3.0 DataStore settings into device-protected
     * storage. DataStore lives in credential-protected storage, so this only
     * runs once the user has unlocked; until then the defaults apply.
     */
    suspend fun migrateLegacyIfNeeded() {
        if (prefs.getBoolean(Keys.LEGACY_MIGRATED, false) || !isUserUnlocked(appContext)) return
        val old = runCatching { appContext.legacyDataStore.data.first() }.getOrNull()
        edit {
            if (old != null) {
                old[booleanPreferencesKey("armed")]?.let { putBoolean(Keys.ARMED, it) }
                old[booleanPreferencesKey("dnd_bypass_enabled")]?.let { putBoolean(Keys.DND_BYPASS_ENABLED, it) }
                old[stringPreferencesKey("dismiss_mode")]?.let { putString(Keys.DISMISS_MODE, it) }
                if (old[booleanPreferencesKey("max_volume_on_ring")] == true) putInt(Keys.VOLUME_PERCENT, 100)
                old[booleanPreferencesKey("auto_update_check")]?.let { putBoolean(Keys.AUTO_UPDATE_CHECK, it) }
                old[stringPreferencesKey("skipped_update_version")]?.let { putString(Keys.SKIPPED_UPDATE_VERSION, it) }
                if (old[booleanPreferencesKey("dnd_override_active")] == true) {
                    old[intPreferencesKey("dnd_prior_filter")]?.let {
                        putBoolean(Keys.DND_OVERRIDE_ACTIVE, true)
                        putInt(Keys.DND_PRIOR_FILTER, it)
                    }
                }
            }
            putBoolean(Keys.LEGACY_MIGRATED, true)
        }
    }

    private fun readDismissMode(p: SharedPreferences): DismissMode = when (p.getString(Keys.DISMISS_MODE, null)) {
        DismissMode.TAP_TO_DISMISS.name -> DismissMode.TAP_TO_DISMISS
        else -> DismissMode.UNLOCK_REQUIRED
    }

    private fun readSoundPolicy(p: SharedPreferences): SoundPolicy =
        SoundPolicy.entries.firstOrNull { it.name == p.getString(Keys.SOUND_POLICY, null) } ?: SoundPolicy.ALLOW_ALL

    companion object {
        private const val PREFS_NAME = "wakelink_settings_de"
        const val DEFAULT_VOLUME_PERCENT = 80
        const val MIN_VOLUME_PERCENT = 30
        const val DEFAULT_SOUND_KEY = "device"

        // Read-modify-write sections across instances (several components
        // create their own SettingsRepository).
        private val LOCK = Any()
    }
}
