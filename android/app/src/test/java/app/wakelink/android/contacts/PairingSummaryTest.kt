package app.wakelink.android.contacts

import app.wakelink.android.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingSummaryTest {
    @Test
    fun coversAllFourDirectionCombinations() {
        assertEquals(R.string.summary_both, pairingSummary(iCanWakeThem = true, theyCanWakeMe = true))
        assertEquals(R.string.summary_only_me, pairingSummary(iCanWakeThem = true, theyCanWakeMe = false))
        assertEquals(R.string.summary_only_them, pairingSummary(iCanWakeThem = false, theyCanWakeMe = true))
        assertEquals(R.string.summary_neither, pairingSummary(iCanWakeThem = false, theyCanWakeMe = false))
    }
}
