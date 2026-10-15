package app.wakelink.android.crypto

import android.content.Context
import app.wakelink.android.network.MediaView
import app.wakelink.android.network.PairingView
import app.wakelink.android.network.TriggerRequest
import app.wakelink.android.network.WakeLinkApi
import app.wakelink.android.sounds.SoundRef
import app.wakelink.android.sounds.parseSoundRef

/**
 * Builds the trigger request for waking [pairing]'s contact.
 *
 * If their app has a device key, everything personal -- this user's name,
 * the chosen sound and picture, and the keys to decrypt those uploads -- is
 * sealed to that key, so neither the server nor FCM can read it. A contact
 * whose app predates end-to-end encryption just gets a plain wake (their
 * app couldn't use the extras anyway).
 */
object WakeComposer {
    suspend fun request(
        context: Context,
        api: WakeLinkApi,
        pairing: PairingView,
        soundKey: String?,
        imageKey: String?,
    ): TriggerRequest {
        val recipientKey = pairing.otherPublicKey ?: return TriggerRequest()
        val keys = MediaKeyStore(context)

        var sound = soundKey
        var soundContentKey: String? = null
        (parseSoundRef(soundKey) as? SoundRef.Custom)?.let { ref ->
            val resolved = resolveCustom(ref.id, keys) { api.listSounds() }
            sound = resolved?.first
            soundContentKey = resolved?.second
        }

        var image: String? = null
        var imageContentKey: String? = null
        (parseSoundRef(imageKey) as? SoundRef.Custom)?.let { ref ->
            resolveCustom(ref.id, keys) { api.listImages() }?.let { (key, contentKey) ->
                image = key
                imageContentKey = contentKey
            }
        }

        val myName = runCatching { api.getMe().displayName }.getOrDefault("")
        val wake = SealedWake(from = myName, sound = sound, soundKey = soundContentKey, image = image, imageKey = imageContentKey)
        return TriggerRequest(sealed = SealedWake.seal(wake, recipientKey))
    }

    /**
     * "custom:<id>:<sha256>" plus the base64 content key for one of this
     * user's uploads, or null if it's gone or this phone doesn't have its key
     * (e.g. uploaded before a reinstall) -- the receiver then uses its own sound.
     */
    private suspend fun resolveCustom(
        id: Long,
        keys: MediaKeyStore,
        list: suspend () -> List<MediaView>,
    ): Pair<String, String>? {
        val contentKey = keys.get(id) ?: return null
        val item = runCatching { list() }.getOrNull()?.firstOrNull { it.id == id } ?: return null
        return SoundRef.Custom(id, item.sha256).key to contentKey.toB64()
    }
}
