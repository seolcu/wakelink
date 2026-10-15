package app.wakelink.android.sounds

import app.wakelink.android.data.SoundPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundRefTest {
    private val sha = "a".repeat(64)

    @Test
    fun parsesEveryKind() {
        assertEquals(SoundRef.Device, parseSoundRef("device"))
        assertEquals(SoundRef.Preset("chime"), parseSoundRef("preset:chime"))
        assertEquals(SoundRef.Custom(12, null), parseSoundRef("custom:12"))
        assertEquals(SoundRef.Custom(12, sha), parseSoundRef("custom:12:$sha"))
    }

    @Test
    fun keysRoundTrip() {
        listOf(SoundRef.Device, SoundRef.Preset("bell"), SoundRef.Custom(3, null), SoundRef.Custom(3, sha))
            .forEach { assertEquals(it, parseSoundRef(it.key)) }
    }

    @Test
    fun rejectsMalformedOrUnknown() {
        listOf(
            null, "", "preset:", "preset:nope", "preset:chime:x", "custom:", "custom:0", "custom:-1",
            "custom:abc", "custom:1:short", "custom:1:${"G".repeat(64)}", "custom:1:$sha:extra", "file:/etc/passwd",
        ).forEach { assertNull("$it should be rejected", parseSoundRef(it)) }
    }

    @Test
    fun receiverPolicyDecides() {
        val own = SoundRef.Preset("bell")
        val preset = SoundRef.Preset("chime")
        val custom = SoundRef.Custom(1, sha)

        assertEquals(own, chooseSound(null, SoundPolicy.ALLOW_ALL, own))
        assertEquals(custom, chooseSound(custom, SoundPolicy.ALLOW_ALL, own))
        assertEquals(preset, chooseSound(preset, SoundPolicy.PRESETS_ONLY, own))
        assertEquals(own, chooseSound(custom, SoundPolicy.PRESETS_ONLY, own))
        assertEquals(own, chooseSound(preset, SoundPolicy.MINE_ONLY, own))
        assertEquals(SoundRef.Device, chooseSound(SoundRef.Device, SoundPolicy.PRESETS_ONLY, own))
    }
}
