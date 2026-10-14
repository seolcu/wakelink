package app.wakelink.android.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinkTest {
    @Test
    fun parsesServerDeepLink() {
        assertEquals("AB23CD45", parseInviteCode("wakelink://invite/AB23CD45"))
    }

    @Test
    fun parsingIsCaseAndWhitespaceTolerant() {
        assertEquals("AB23CD45", parseInviteCode("  WakeLink://Invite/ab23cd45/ "))
        assertEquals("AB23CD45", parseInviteCode("wakelink://invite/AB23CD45?utm=x#frag"))
    }

    @Test
    fun rejectsOtherLinks() {
        assertNull(parseInviteCode(null))
        assertNull(parseInviteCode(""))
        assertNull(parseInviteCode("https://wake.seolcu.com/other/AB23CD45"))
        assertNull(parseInviteCode("https://wake.seolcu.com/invite/"))
        assertNull(parseInviteCode("ftp://wake.seolcu.com/invite/AB23CD45"))
        assertNull(parseInviteCode("wakelink://invite/"))
        assertNull(parseInviteCode("wakelink://other/AB23CD45"))
    }

    @Test
    fun parsesWebInviteLinks() {
        assertEquals("AB23CD45", parseInviteCode("https://wake.seolcu.com/invite/AB23CD45"))
        assertEquals("AB23CD45", parseInviteCode(" HTTPS://Wake.Seolcu.com/invite/ab23cd45?utm=kakao#x "))
        assertEquals("AB23CD45", parseInviteCode("http://localhost:8080/invite/AB23CD45/"))
    }

    @Test
    fun buildsWebInviteLink() {
        assertEquals("https://wake.seolcu.com/invite/AB23CD45", inviteWebLink("https://wake.seolcu.com/", "AB23CD45"))
        assertEquals("https://wake.seolcu.com/invite/AB23CD45", inviteWebLink("https://wake.seolcu.com", "AB23CD45"))
    }

    @Test
    fun normalizesTypedOrPastedCodes() {
        assertEquals("AB23CD45", normalizeInviteCode("ab23-cd45"))
        assertEquals("AB23CD45", normalizeInviteCode(" ab23 cd45 "))
        assertEquals("AB23CD45", normalizeInviteCode("wakelink://invite/AB23CD45"))
        assertEquals("AB23CD45", normalizeInviteCode("https://wake.seolcu.com/invite/AB23CD45"))
        assertEquals("", normalizeInviteCode(""))
    }

    @Test
    fun formatsRemainingTime() {
        assertEquals("10:00", formatRemaining(600))
        assertEquals("0:09", formatRemaining(9))
        assertEquals("0:00", formatRemaining(-5))
    }
}
