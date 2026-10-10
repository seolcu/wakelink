package app.wakelink.android.ui

import app.wakelink.android.ui.components.avatarColorIndex
import app.wakelink.android.ui.components.avatarInitial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarTest {
    @Test
    fun initialIsFirstVisibleCharacter() {
        assertEquals("민", avatarInitial("민수"))
        assertEquals("J", avatarInitial("  jiwoo"))
        assertEquals("?", avatarInitial("   "))
    }

    @Test
    fun colorIsStableAndInRange() {
        repeat(50) { i ->
            val idx = avatarColorIndex("user$i", 6)
            assertTrue(idx in 0 until 6)
        }
        assertEquals(avatarColorIndex("민수", 6), avatarColorIndex(" 민수 ", 6))
    }
}
