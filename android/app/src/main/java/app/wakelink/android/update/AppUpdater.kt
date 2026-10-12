package app.wakelink.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.wakelink.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The newest published release, when it's newer than what's installed. */
data class UpdateInfo(
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Sideload self-update via GitHub Releases: the app isn't on a store, so
 * it checks `releases/latest` of [BuildConfig.UPDATE_REPO] itself,
 * downloads the [APK_ASSET_NAME] asset and hands it to the system
 * installer. Android always asks the user to confirm the install, and
 * refuses it unless the APK is signed with the same key as the installed
 * app -- that signature check is what makes trusting a download URL safe.
 */
object AppUpdater {
    /** Every release uploads its APK under this exact name (see scripts/release-android.sh). */
    const val APK_ASSET_NAME = "wakelink.apk"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val isEnabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Returns the latest release if it's newer than this build, null if up to date. Throws on network errors. */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext null
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = http.newCall(request).execute().use { response ->
            // 404 = no release published yet.
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("empty response")
        }
        val release = JSONObject(body)
        val tag = release.getString("tag_name")
        if (!isNewerVersion(tag, BuildConfig.VERSION_NAME)) return@withContext null

        val assets = release.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name") == APK_ASSET_NAME) {
                return@withContext UpdateInfo(
                    versionName = tag.removePrefix("v"),
                    notes = release.optString("body").trim(),
                    apkUrl = asset.getString("browser_download_url"),
                    sizeBytes = asset.optLong("size"),
                )
            }
        }
        null
    }

    /** Downloads the APK into the app cache, reporting progress in 0..1 when the size is known. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "wakelink-${info.versionName}.apk")

            http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            verifyApk(context, target)
            target
        }

    /**
     * Cheap sanity check before bothering the user with the installer: the
     * file must parse as an APK for this same package. (The installer
     * itself enforces the signature match.)
     */
    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, apk: File) {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (info?.packageName != context.packageName) {
            apk.delete()
            throw IOException("downloaded file is not a WakeLink APK")
        }
    }

    /**
     * Android needs a per-app "install unknown apps" grant before an app may
     * open the installer. False means [openInstallPermissionSettings] should
     * be shown first.
     */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
