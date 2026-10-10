package app.wakelink.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorsTest {
    @Test
    fun extractsServerErrorField() {
        assertEquals("invite expired", parseServerError("""{"error":"invite expired"}"""))
        assertNull(parseServerError(null))
        assertNull(parseServerError(""))
        assertNull(parseServerError("<html>bad gateway</html>"))
        assertNull(parseServerError("""{"status":"ok"}"""))
    }

    @Test
    fun distinguishesCasesThatShareAStatusCode() {
        assertTrue(userMessageFor(429, "daily trigger limit reached").contains("횟수"))
        assertTrue(userMessageFor(429, "cooldown active, try again shortly").contains("잠시 후"))
        assertTrue(userMessageFor(409, "invite already used").contains("이미 사용된"))
        assertTrue(userMessageFor(409, "already paired").contains("이미 연결된"))
        assertTrue(userMessageFor(400, "cannot pair with yourself").contains("내가 만든"))
    }

    @Test
    fun coversTransportAndUnknownFailures() {
        assertTrue(userMessageFor(null, null).contains("네트워크"))
        assertTrue(userMessageFor(503, null).contains("서버 오류"))
        assertEquals("요청에 실패했어요 (HTTP 418)", userMessageFor(418, null))
    }
}
