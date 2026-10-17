package app.wakelink.android.data

import app.wakelink.android.R
import app.wakelink.android.ui.UiText
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArmedPauseTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun pauseEndsByItself() {
        val now = at(17, 22)
        assertTrue(isArmedAt(true, null, now))
        assertFalse(isArmedAt(false, null, now))
        assertFalse(isArmedAt(false, now + 1, now))
        assertTrue(isArmedAt(false, now, now))
        assertTrue(isArmedAt(false, now - 60_000, now))
    }

    @Test
    fun labelsTheResumeTime() {
        val now = at(17, 22)
        assertEquals(UiText(R.string.armed_resumes_today, listOf("23:30")), resumeLabel(at(17, 23, 30), now, zone))
        assertEquals(UiText(R.string.armed_resumes_tomorrow, listOf("07:00")), resumeLabel(at(18, 7), now, zone))
        assertEquals(UiText(R.string.armed_resumes_later, listOf("10/20 07:00")), resumeLabel(at(20, 7), now, zone))
    }
}
