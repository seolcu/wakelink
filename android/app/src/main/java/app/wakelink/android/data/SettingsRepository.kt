package app.wakelink.android.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wakelink_settings")

enum class DismissMode { UNLOCK_REQUIRED, TAP_TO_DISMISS }

/**
 * User-facing settings plus the small bit of durable state the DND-bypass
 * self-healing guard needs (see AlarmForegroundService / BootCompletedReceiver):
 * if the process dies mid-ring with DND overridden, [isDndOverrideActive]
 * and [consumePendingDndOverride] let a later app start restore it, instead
 * of leaving the phone stuck on a DND state it didn't choose.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val ARMED = booleanPreferencesKey("armed")
        val DND_BYPASS_ENABLED = booleanPreferencesKey("dnd_bypass_enabled")
        val DISMISS_MODE = stringPreferencesKey("dismiss_mode")
        val MAX_VOLUME_ON_RING = booleanPreferencesKey("max_volume_on_ring")
        val DND_OVERRIDE_ACTIVE = booleanPreferencesKey("dnd_override_active")
        val DND_PRIOR_FILTER = intPreferencesKey("dnd_prior_filter")
        val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
        val SKIPPED_UPDATE_VERSION = stringPreferencesKey("skipped_update_version")
    }

    val armed: Flow<Boolean> = context.dataStore.data.map { it[Keys.ARMED] ?: true }
    val dndBypassEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.DND_BYPASS_ENABLED] ?: false }
    val dismissMode: Flow<DismissMode> = context.dataStore.data.map {
        when (it[Keys.DISMISS_MODE]) {
            DismissMode.TAP_TO_DISMISS.name -> DismissMode.TAP_TO_DISMISS
            else -> DismissMode.UNLOCK_REQUIRED
        }
    }
    val maxVolumeOnRing: Flow<Boolean> = context.dataStore.data.map { it[Keys.MAX_VOLUME_ON_RING] ?: false }
    val autoUpdateCheck: Flow<Boolean> = context.dataStore.data.map { it[Keys.AUTO_UPDATE_CHECK] ?: true }

    /** Version the user chose "skip" for in the startup update prompt; manual checks ignore it. */
    val skippedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[Keys.SKIPPED_UPDATE_VERSION] }

    suspend fun setArmed(value: Boolean) = context.dataStore.edit { it[Keys.ARMED] = value }
    suspend fun setDndBypassEnabled(value: Boolean) = context.dataStore.edit { it[Keys.DND_BYPASS_ENABLED] = value }
    suspend fun setDismissMode(value: DismissMode) = context.dataStore.edit { it[Keys.DISMISS_MODE] = value.name }
    suspend fun setMaxVolumeOnRing(value: Boolean) = context.dataStore.edit { it[Keys.MAX_VOLUME_ON_RING] = value }
    suspend fun setAutoUpdateCheck(value: Boolean) = context.dataStore.edit { it[Keys.AUTO_UPDATE_CHECK] = value }
    suspend fun setSkippedUpdateVersion(value: String) = context.dataStore.edit { it[Keys.SKIPPED_UPDATE_VERSION] = value }

    suspend fun isDndOverrideActive(): Boolean =
        context.dataStore.data.map { it[Keys.DND_OVERRIDE_ACTIVE] ?: false }.first()

    suspend fun savePendingDndOverride(priorFilter: Int) {
        context.dataStore.edit {
            it[Keys.DND_OVERRIDE_ACTIVE] = true
            it[Keys.DND_PRIOR_FILTER] = priorFilter
        }
    }

    /** Clears the pending-override flag and returns the filter to restore to, if any. */
    suspend fun consumePendingDndOverride(): Int? {
        val filter = context.dataStore.data.map { it[Keys.DND_PRIOR_FILTER] }.first()
        context.dataStore.edit {
            it[Keys.DND_OVERRIDE_ACTIVE] = false
            it.remove(Keys.DND_PRIOR_FILTER)
        }
        return filter
    }
}
