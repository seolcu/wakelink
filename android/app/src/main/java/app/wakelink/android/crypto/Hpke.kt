package app.wakelink.android.crypto

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import java.util.Base64

/**
 * Public-key encryption between devices (HPKE: X25519 + HKDF-SHA256 +
 * AES-256-GCM, via Tink). Pure JVM code so it's unit-testable; key storage
 * on the device lives in [DeviceKeys].
 */
object Hpke {
    const val KEY_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

    /** Binds ciphertexts to their purpose so one can't be replayed as another. */
    val WAKE_CONTEXT = "wakelink-wake-v1".toByteArray()

    init {
        HybridConfig.register()
    }

    fun generate(): KeysetHandle = KeysetHandle.generateNew(KeyTemplates.get(KEY_TEMPLATE))

    /** The shareable public half of [handle], base64 (what the server stores). */
    fun exportPublic(handle: KeysetHandle): String =
        Base64.getEncoder().encodeToString(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle.publicKeysetHandle))

    fun encrypt(publicKeyB64: String, plaintext: ByteArray, context: ByteArray = WAKE_CONTEXT): ByteArray {
        val publicHandle = TinkProtoKeysetFormat.parseKeysetWithoutSecret(Base64.getDecoder().decode(publicKeyB64))
        return publicHandle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java).encrypt(plaintext, context)
    }

    /** Throws (GeneralSecurityException) if [ciphertext] wasn't made for [handle]. */
    fun decrypt(handle: KeysetHandle, ciphertext: ByteArray, context: ByteArray = WAKE_CONTEXT): ByteArray =
        handle.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java).decrypt(ciphertext, context)
}
