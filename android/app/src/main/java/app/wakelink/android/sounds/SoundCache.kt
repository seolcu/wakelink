package app.wakelink.android.sounds

import android.content.Context
import app.wakelink.android.crypto.MediaCrypto
import app.wakelink.android.data.TokenStore
import app.wakelink.android.data.isUserUnlocked
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.WakeLinkApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ResponseBody
import java.io.File
import java.security.MessageDigest

/**
 * Local, decrypted copies of senders' uploads (recordings/files, images),
 * named by the SHA-256 of what the server stores (the ciphertext, for
 * end-to-end encrypted uploads). Integrity is checked once, on download:
 * the hash must match the one in the push and the AES-GCM tag must verify.
 *
 * Kept in device-protected storage so something fetched earlier still
 * plays/shows when a wake arrives before the first unlock after a reboot.
 * Fetching needs the API key, which only exists once the device is unlocked.
 */
class MediaCache(
    private val dirName: String,
    private val fetch: suspend (WakeLinkApi, Long) -> ResponseBody,
) {
    private companion object {
        const val MAX_FILES = 30
    }

    private fun dir(context: Context): File =
        File(context.createDeviceProtectedStorageContext().filesDir, dirName).apply { mkdirs() }

    /** The cached (already verified and decrypted) file for [ref], if present. */
    fun cached(context: Context, ref: SoundRef.Custom): File? {
        val sha = ref.sha256 ?: return null
        return File(dir(context), sha).takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Cached copy, or a fresh download within [timeoutMs] whose hash matches
     * the one in the push. Null if neither works out -- callers must then do
     * without (a fallback sound, no picture), never fail the wake.
     */
    suspend fun obtain(context: Context, ref: SoundRef.Custom, timeoutMs: Long, contentKey: ByteArray? = null): File? {
        cached(context, ref)?.let { return it }
        val sha = ref.sha256 ?: return null
        if (!isUserUnlocked(context)) return null
        return withTimeoutOrNull(timeoutMs) {
            runCatching { download(context, ref.id, sha, contentKey) }.getOrNull()
        }
    }

    /** Stores bytes we already have (e.g. something this user just uploaded) under their hash. */
    fun put(context: Context, sha: String, bytes: ByteArray) {
        File(dir(context), sha).writeBytes(bytes)
        prune(context)
    }

    private suspend fun download(context: Context, id: Long, sha: String, contentKey: ByteArray?): File? =
        withContext(Dispatchers.IO) {
            val tokenStore = TokenStore(context)
            if (!tokenStore.isRegistered) return@withContext null
            val stored = fetch(ApiClient.create(tokenStore), id).use { it.bytes() }
            if (sha256Hex(stored) != sha) return@withContext null
            val plain = when {
                MediaCrypto.isEncrypted(stored) -> MediaCrypto.decrypt(contentKey ?: return@withContext null, stored)
                else -> stored // uploaded by an app from before end-to-end encryption
            }
            val target = File(dir(context), sha)
            val partial = File(dir(context), "$sha.part")
            partial.writeBytes(plain)
            partial.renameTo(target)
            prune(context)
            target
        }

    private fun prune(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }
}

val SoundCache = MediaCache("sounds") { api, id -> api.soundAudio(id) }
val ImageCache = MediaCache("images") { api, id -> api.imageData(id) }

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
