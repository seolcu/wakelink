package app.wakelink.android.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingSummaryTest {
    @Test
    fun coversAllFourDirectionCombinations() {
        assertEquals("서로 깨울 수 있어요", pairingSummary(iCanWakeThem = true, theyCanWakeMe = true))
        assertTrue(pairingSummary(iCanWakeThem = true, theyCanWakeMe = false).startsWith("나만"))
        assertTrue(pairingSummary(iCanWakeThem = false, theyCanWakeMe = true).startsWith("상대만"))
        assertEquals("둘 다 깨우기가 꺼져 있어요", pairingSummary(iCanWakeThem = false, theyCanWakeMe = false))
    }
}
