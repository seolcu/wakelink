package app.wakelink.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Holds this device's WakeLink identity (user id + API key) in
 * EncryptedSharedPreferences. There is exactly one identity per install --
 * re-pairing after a reinstall simply calls [save] again, which cleanly
 * supersedes the old credential (the backend has no way to reach the
 * uninstalled app anyway).
 */
class TokenStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wakelink_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    val userId: Long
        get() = prefs.getLong(KEY_USER_ID, -1L)

    val apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)

    val isRegistered: Boolean
        get() = apiKey != null

    fun save(userId: Long, apiKey: String) {
        prefs.edit()
            .putLong(KEY_USER_ID, userId)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_API_KEY = "api_key"
    }
}
