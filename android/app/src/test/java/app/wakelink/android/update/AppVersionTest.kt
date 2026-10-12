package app.wakelink.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun parsesTagsAndVersionNames() {
        assertEquals(listOf(0, 2, 0), parseVersion("v0.2.0"))
        assertEquals(listOf(1, 10), parseVersion(" 1.10 "))
    }

    @Test
    fun rejectsNonNumericVersions() {
        assertNull(parseVersion("0.2.0-rc1"))
        assertNull(parseVersion("latest"))
        assertNull(parseVersion(""))
    }

    @Test
    fun comparesNumericallyNotLexically() {
        assertTrue(isNewerVersion("v0.10.0", "0.9.0"))
        assertTrue(isNewerVersion("v1.0.0", "0.99.99"))
        assertTrue(isNewerVersion("v0.2.1", "0.2"))
    }

    @Test
    fun sameOrOlderIsNotAnUpdate() {
        assertFalse(isNewerVersion("v0.2.0", "0.2.0"))
        assertFalse(isNewerVersion("v0.2", "0.2.0"))
        assertFalse(isNewerVersion("v0.1.9", "0.2.0"))
        assertFalse(isNewerVersion("v0.3.0-beta", "0.2.0"))
    }
}
