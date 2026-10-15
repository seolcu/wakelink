package app.wakelink.android.images

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import app.wakelink.android.crypto.MediaCrypto
import app.wakelink.android.crypto.MediaKeyStore
import app.wakelink.android.network.MediaView
import app.wakelink.android.sounds.CustomSounds
import app.wakelink.android.network.WakeLinkApi
import app.wakelink.android.sounds.ImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Preparing and uploading pictures to show on the receiver's alarm screen. */
object ImageUploads {
    /** Must match the server's limit (imageMedia.maxBytes). */
    const val MAX_BYTES = 3 * 1024 * 1024
    private const val MAX_EDGE = 1280
    private const val JPEG_QUALITY = 85
    const val MAX_NAME_LENGTH = 40

    sealed interface Prepared {
        data class Ok(val name: String, val bytes: ByteArray, val mime: String) : Prepared
        data object TooLarge : Prepared
        data object Unreadable : Prepared
    }

    /**
     * GIFs are kept byte-for-byte so they still animate (if small enough);
     * everything else is decoded, turned upright and re-encoded as a JPEG of
     * at most [MAX_EDGE] px, which keeps photos from a camera well under the limit.
     */
    suspend fun prepare(context: Context, uri: Uri): Prepared = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)
            val name = displayName(context, uri)
            if (mime == "image/gif") {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytesUpTo(MAX_BYTES + 1) }
                    ?: return@withContext Prepared.Unreadable
                return@withContext if (bytes.size > MAX_BYTES) Prepared.TooLarge else Prepared.Ok(name, bytes, "image/gif")
            }
            val bitmap = decodeUpright(context, uri) ?: return@withContext Prepared.Unreadable
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            val bytes = out.toByteArray()
            if (bytes.size > MAX_BYTES) Prepared.TooLarge else Prepared.Ok(name, bytes, "image/jpeg")
        } catch (_: Exception) {
            Prepared.Unreadable
        }
    }

    suspend fun upload(
        context: Context,
        api: WakeLinkApi,
        name: String,
        bytes: ByteArray,
        @Suppress("UNUSED_PARAMETER") mime: String, // the decoder sniffs it after decryption
    ): MediaView {
        // End to end: the server only ever gets ciphertext (see CustomSounds.upload).
        val key = MediaCrypto.newKey()
        val blob = MediaCrypto.encrypt(key, bytes)
        val created = api.uploadImage(name.take(MAX_NAME_LENGTH), blob.toRequestBody(CustomSounds.ENCRYPTED_MIME.toMediaType()))
        withContext(Dispatchers.IO) {
            MediaKeyStore(context).put(created.id, key)
            // Keep a decrypted local copy so thumbnails don't need a download.
            runCatching { ImageCache.put(context, created.sha256, bytes) }
        }
        return created
    }

    /** A camera capture target this app can hand to the camera app (see res/xml/update_paths.xml). */
    fun captureFile(context: Context): File =
        File(context.cacheDir, "capture").apply { mkdirs() }.let { File(it, "photo.jpg") }

    private fun displayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()?.substringBeforeLast('.')?.take(MAX_NAME_LENGTH)?.ifBlank { null } ?: "Photo"

    // The framework ExifInterface's InputStream bugs were fixed in Android 7.1;
    // minSdk is 26, so the androidx copy would only add a dependency.
    @SuppressLint("ExifInterface")
    private fun decodeUpright(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val scale = minOf(1f, MAX_EDGE.toFloat() / max(decoded.width, decoded.height))
        if (rotation == 0f && scale >= 1f) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation)
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }
}

private fun java.io.InputStream.readBytesUpTo(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < limit) {
        val n = read(buffer, total, limit - total)
        if (n < 0) break
        total += n
    }
    return buffer.copyOf(total)
}
