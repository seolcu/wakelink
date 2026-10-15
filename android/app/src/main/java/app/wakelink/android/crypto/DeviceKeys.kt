package app.wakelink.android.crypto

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * This device's end-to-end encryption key pair. The private half never
 * leaves the phone: Tink keeps the keyset in credential-protected storage,
 * wrapped by a key in the Android Keystore. Only the public half is
 * uploaded (see [publishIfNeeded]), for contacts to encrypt to.
 *
 * Credential-protected means it's unavailable before the first unlock after
 * a reboot; wakes arriving then still ring, just without the sealed details.
 */
object DeviceKeys {
    private const val KEYSET_NAME = "device_hpke_keyset"
    private const val PREFS = "wakelink_device_keys"
    private const val MASTER_KEY_URI = "android-keystore://wakelink_device_keys_master"
    private const val KEY_PUBLISHED = "published_public_key"

    @Volatile
    private var handle: KeysetHandle? = null

    fun keyset(context: Context): KeysetHandle = handle ?: synchronized(this) {
        handle ?: AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, KEYSET_NAME, PREFS)
            .withKeyTemplate(KeyTemplates.get(Hpke.KEY_TEMPLATE))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .also { handle = it }
    }

    fun publicKey(context: Context): String = Hpke.exportPublic(keyset(context))

    fun decrypt(context: Context, ciphertext: ByteArray): ByteArray = Hpke.decrypt(keyset(context), ciphertext)

    /**
     * Uploads the public key unless this exact key was already published;
     * [upload] does the API call. Run after registering and on every
     * unlocked start (it's a no-op once done).
     */
    suspend fun publishIfNeeded(context: Context, upload: suspend (String) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = publicKey(context)
        if (prefs.getString(KEY_PUBLISHED, null) == key) return
        upload(key)
        prefs.edit().putString(KEY_PUBLISHED, key).apply()
    }

    /** Forget that the key was published, e.g. after re-registering as a new account. */
    fun markUnpublished(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PUBLISHED).apply()
    }
}

/**
 * The per-upload content keys of this user's own recordings/files/images
 * (see [MediaCrypto]), by upload id. Without its key an upload can't be
 * sent any more -- e.g. after reinstalling the app.
 */
class MediaKeyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wakelink_media_keys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(mediaId: Long): ByteArray? = prefs.getString(mediaId.toString(), null)?.fromB64()

    fun put(mediaId: Long, key: ByteArray) {
        prefs.edit().putString(mediaId.toString(), key.toB64()).apply()
    }

    fun remove(mediaId: Long) {
        prefs.edit().remove(mediaId.toString()).apply()
    }
}
