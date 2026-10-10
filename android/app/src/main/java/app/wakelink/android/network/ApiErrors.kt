package app.wakelink.android.network

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
fun userMessageFor(status: Int?, serverError: String?): String = when (status) {
    null -> "서버에 연결할 수 없어요. 네트워크를 확인해 주세요"
    400 -> when (serverError) {
        "cannot pair with yourself" -> "내가 만든 코드는 내가 입력할 수 없어요"
        "display_name is required" -> "이름을 입력해 주세요"
        "display_name is too long" -> "이름이 너무 길어요 (최대 40자)"
        else -> "잘못된 요청이에요"
    }
    401 -> "인증 정보가 유효하지 않아요. 앱을 다시 설정해 주세요"
    403 -> when (serverError) {
        "not permitted to wake this contact" -> "이 연락처를 깨우는 설정이 꺼져 있어요"
        else -> "권한이 없어요"
    }
    404 -> when (serverError) {
        "invite not found" -> "존재하지 않는 초대 코드예요"
        "pairing not found" -> "이미 끊어진 연결이에요"
        else -> "찾을 수 없어요"
    }
    409 -> when (serverError) {
        "invite already used" -> "이미 사용된 초대 코드예요"
        "already paired" -> "이미 연결된 상대예요"
        else -> "요청이 충돌했어요"
    }
    410 -> "만료된 초대 코드예요. 새 코드를 받아 주세요"
    422 -> "상대방 기기가 알림을 받을 수 있게 등록되어 있지 않아요. 상대방이 앱을 한 번 열어야 해요"
    429 -> when (serverError) {
        "daily trigger limit reached" -> "오늘 이 연락처를 깨울 수 있는 횟수를 모두 썼어요"
        else -> "너무 자주 보냈어요. 잠시 후 다시 시도해 주세요"
    }
    502 -> "푸시 전송에 실패했어요. 잠시 후 다시 시도해 주세요"
    in 500..599 -> "서버 오류가 발생했어요"
    else -> "요청에 실패했어요 (HTTP $status)"
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

fun Throwable.toUserMessage(): String = when (this) {
    is HttpException -> userMessageFor(code(), parseServerError(response()?.errorBody()?.string()))
    is IOException -> userMessageFor(null, null)
    else -> message ?: "알 수 없는 오류"
}
