package app.wakelink.android.network

import app.wakelink.android.R
import app.wakelink.android.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(UiText(R.string.error_daily_cap), userMessageFor(429, "daily trigger limit reached"))
        assertEquals(UiText(R.string.error_cooldown), userMessageFor(429, "cooldown active, try again shortly"))
        assertEquals(UiText(R.string.error_invite_used), userMessageFor(409, "invite already used"))
        assertEquals(UiText(R.string.error_already_paired), userMessageFor(409, "already paired"))
        assertEquals(UiText(R.string.error_self_pair), userMessageFor(400, "cannot pair with yourself"))
    }

    @Test
    fun coversTransportAndUnknownFailures() {
        assertEquals(UiText(R.string.error_network), userMessageFor(null, null))
        assertEquals(UiText(R.string.error_server), userMessageFor(503, null))
        assertEquals(UiText(R.string.error_http_status, listOf(418)), userMessageFor(418, null))
    }
}
