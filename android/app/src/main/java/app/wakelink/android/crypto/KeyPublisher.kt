package app.wakelink.android.crypto

import android.content.Context
import app.wakelink.android.data.TokenStore
import app.wakelink.android.data.isUserUnlocked
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.PublicKeyRequest
import app.wakelink.android.network.orThrow

/** Makes sure the server has this device's current public key (see [DeviceKeys]). */
object KeyPublisher {
    /** [freshAccount]: just registered, so the server has no key for this account yet. */
    suspend fun publish(context: Context, freshAccount: Boolean = false) {
        if (!isUserUnlocked(context)) return
        val tokenStore = TokenStore(context)
        if (!tokenStore.isRegistered) return
        if (freshAccount) DeviceKeys.markUnpublished(context)
        runCatching {
            DeviceKeys.publishIfNeeded(context) { key ->
                ApiClient.create(tokenStore).putPublicKey(PublicKeyRequest(key)).orThrow()
            }
        }
    }
}
