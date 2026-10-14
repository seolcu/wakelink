package app.wakelink.android.network

import android.content.Context
import app.wakelink.android.R
import app.wakelink.android.ui.UiText
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.IOException
import retrofit2.HttpException
import retrofit2.Response

private val errorBodyAdapter = Moshi.Builder().build()
    .adapter<Map<String, Any?>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

/** Extracts the `error` field of the server's `{"error": "..."}` body, if any. */
fun parseServerError(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return runCatching { errorBodyAdapter.fromJson(body)?.get("error") as? String }.getOrNull()
}

/**
 * Maps an HTTP status (null = never reached the server) plus the server's
 * error string to something a user can act on. Server strings are matched
 * exactly where one status code covers several distinct situations -- see
 * server/internal/api for where each one comes from.
 */
fun userMessageFor(status: Int?, serverError: String?): UiText = when (status) {
    null -> UiText(R.string.error_network)
    400 -> when (serverError) {
        "cannot pair with yourself" -> UiText(R.string.error_self_pair)
        "display_name is required" -> UiText(R.string.error_name_required)
        "display_name is too long" -> UiText(R.string.error_name_too_long)
        else -> UiText(R.string.error_bad_request)
    }
    401 -> UiText(R.string.error_unauthorized)
    403 -> when (serverError) {
        "not permitted to wake this contact" -> UiText(R.string.error_wake_not_permitted)
        else -> UiText(R.string.error_forbidden)
    }
    404 -> when (serverError) {
        "invite not found" -> UiText(R.string.error_invite_not_found)
        "pairing not found" -> UiText(R.string.error_pairing_not_found)
        else -> UiText(R.string.error_not_found)
    }
    409 -> when (serverError) {
        "invite already used" -> UiText(R.string.error_invite_used)
        "already paired" -> UiText(R.string.error_already_paired)
        else -> UiText(R.string.error_conflict)
    }
    410 -> UiText(R.string.error_invite_expired)
    422 -> UiText(R.string.error_recipient_unregistered)
    429 -> when (serverError) {
        "daily trigger limit reached" -> UiText(R.string.error_daily_cap)
        else -> UiText(R.string.error_cooldown)
    }
    502 -> UiText(R.string.error_push_failed)
    in 500..599 -> UiText(R.string.error_server)
    else -> UiText(R.string.error_http_status, listOf(status))
}

/**
 * Retrofit only throws for non-2xx on endpoints that return a body type;
 * the `Response<Unit>` ones (204s, plain "ok"s) must be checked by hand or
 * a failure silently looks like success.
 */
fun <T> Response<T>.orThrow(): Response<T> {
    if (!isSuccessful) throw HttpException(this)
    return this
}

fun Throwable.toUserText(): UiText = when (this) {
    is HttpException -> userMessageFor(code(), parseServerError(response()?.errorBody()?.string()))
    is IOException -> userMessageFor(null, null)
    else -> UiText(R.string.common_unknown_error)
}

/** [toUserText] resolved in the current language, for "<what failed>: <why>" messages. */
fun Throwable.toUserMessage(context: Context): String = toUserText().resolve(context)
