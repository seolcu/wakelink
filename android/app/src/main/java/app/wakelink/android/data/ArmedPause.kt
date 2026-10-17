package app.wakelink.android.data

import app.wakelink.android.R
import app.wakelink.android.ui.UiText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Whether wakes ring at [nowMillis]: switched on, or paused until [resumeAtMillis] and that has passed. */
fun isArmedAt(switchedOn: Boolean, resumeAtMillis: Long?, nowMillis: Long): Boolean =
    switchedOn || (resumeAtMillis != null && nowMillis >= resumeAtMillis)

/** What switching wake-ups off offers, in hours; null = until switched back on. */
val PAUSE_CHOICES_HOURS: List<Int?> = listOf(1, 3, 8, 12, null)

/** "Back on at 07:30" / "back on tomorrow at 07:30" / "back on 10/18 07:30". */
fun resumeLabel(resumeAtMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): UiText {
    val at = Instant.ofEpochMilli(resumeAtMillis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val time = at.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when (at.toLocalDate()) {
        today -> UiText(R.string.armed_resumes_today, listOf(time))
        today.plusDays(1) -> UiText(R.string.armed_resumes_tomorrow, listOf(time))
        else -> UiText(R.string.armed_resumes_later, listOf(at.format(DateTimeFormatter.ofPattern("M/d HH:mm"))))
    }
}
