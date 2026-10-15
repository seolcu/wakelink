package app.wakelink.android.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric encryption of uploaded recordings/files/images, done on the
 * sender's phone before upload, so the server only ever stores ciphertext.
 * Each upload gets its own random key, which the sender keeps
 * ([MediaKeyStore]) and hands to a receiver inside the sealed wake payload.
 *
 * Format: "WLE1" magic, 12-byte nonce, AES-256-GCM ciphertext+tag.
 */
object MediaCrypto {
    val MAGIC = "WLE1".toByteArray()
    private const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC)
        return MAGIC + nonce + cipher.doFinal(plaintext)
    }

    /** Throws if [blob] isn't ours or was tampered with (the GCM tag won't verify). */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(isEncrypted(blob) && blob.size > MAGIC.size + NONCE_BYTES) { "not an encrypted WakeLink blob" }
        val nonce = blob.copyOfRange(MAGIC.size, MAGIC.size + NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(MAGIC)
        return cipher.doFinal(blob, MAGIC.size + NONCE_BYTES, blob.size - MAGIC.size - NONCE_BYTES)
    }
}
