package app.wakelink.android.crypto

import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.Base64

/**
 * Everything about a wake that only the receiver should see, encrypted to
 * the receiver's device key: who it's from, the chosen sound and picture,
 * and the keys to decrypt those uploads. The server and FCM only relay it.
 */
data class SealedWake(
    val v: Int = 1,
    val from: String,
    /** A SoundRef key; custom ones as "custom:<id>:<sha256 of the ciphertext>". */
    val sound: String? = null,
    @Json(name = "sound_key") val soundKey: String? = null,
    /** "custom:<id>:<sha256 of the ciphertext>" or null. */
    val image: String? = null,
    @Json(name = "image_key") val imageKey: String? = null,
) {
    companion object {
        private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(SealedWake::class.java)

        /** Encrypts [wake] to [recipientPublicKey]; the result goes in the trigger's "sealed" field. */
        fun seal(wake: SealedWake, recipientPublicKey: String): String =
            Base64.getEncoder().encodeToString(Hpke.encrypt(recipientPublicKey, adapter.toJson(wake).toByteArray()))

        /** Null if it isn't for this device's key (e.g. sealed to a key from before a reinstall) or malformed. */
        fun open(sealedB64: String, decrypt: (ByteArray) -> ByteArray): SealedWake? = runCatching {
            adapter.fromJson(String(decrypt(Base64.getDecoder().decode(sealedB64))))
        }.getOrNull()
    }
}

fun ByteArray.toB64(): String = Base64.getEncoder().encodeToString(this)
fun String.fromB64(): ByteArray? = runCatching { Base64.getDecoder().decode(this) }.getOrNull()
