package app.wakelink.android.sounds

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.wakelink.android.crypto.MediaCrypto
import app.wakelink.android.crypto.MediaKeyStore
import app.wakelink.android.network.MediaView
import app.wakelink.android.network.WakeLinkApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Uploading the user's own recordings and audio files (see server/internal/api/sounds.go). */
object CustomSounds {
    /** Must match the server's limit (maxSoundBytes). */
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_RECORDING_MS = 15_000
    const val RECORDING_MIME = "audio/mp4"
    const val MAX_NAME_LENGTH = 40
    const val ENCRYPTED_MIME = "application/octet-stream"

    sealed interface Import {
        data class Ok(val name: String, val bytes: ByteArray, val mime: String) : Import
        data object TooLarge : Import
        data object Unreadable : Import
    }

    fun readImport(context: Context, uri: Uri): Import = try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.substringBeforeLast('.')?.take(MAX_NAME_LENGTH)?.ifBlank { null } ?: "Audio"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            // Read one byte past the limit to tell "exactly 2 MB" from "bigger".
            val buffer = ByteArray(MAX_BYTES + 1)
            var total = 0
            while (total < buffer.size) {
                val n = input.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            buffer.copyOf(total)
        }
        when {
            bytes == null || bytes.isEmpty() -> Import.Unreadable
            bytes.size > MAX_BYTES -> Import.TooLarge
            else -> Import.Ok(name, bytes, mime)
        }
    } catch (_: Exception) {
        Import.Unreadable
    }

    suspend fun upload(
        context: Context,
        api: WakeLinkApi,
        kind: String,
        name: String,
        bytes: ByteArray,
        @Suppress("UNUSED_PARAMETER") mime: String, // sniffed by the player after decryption
    ): MediaView {
        // End to end: the server only ever gets ciphertext. The key stays on
        // this phone and travels to a receiver inside a sealed wake.
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.encrypt(key, bytes)
        val created = api.uploadSound(kind, name.take(MAX_NAME_LENGTH), blob.toRequestBody(ENCRYPTED_MIME.toMediaType()))
        withContext(Dispatchers.IO) {
            MediaKeyStore(context).put(created.id, key)
            // Keep a decrypted local copy so previews don't re-download it.
            runCatching { SoundCache.put(context, created.sha256, bytes) }
        }
        return created
    }
}
