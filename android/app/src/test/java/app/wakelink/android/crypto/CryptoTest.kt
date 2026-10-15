package app.wakelink.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    @Test
    fun mediaRoundTripsAndRejectsTampering() {
        val key = MediaCrypto.newKey()
        val plain = "hello alarm".toByteArray()
        val blob = MediaCrypto.encrypt(key, plain)
        assertTrue(MediaCrypto.isEncrypted(blob))
        assertArrayEquals(plain, MediaCrypto.decrypt(key, blob))

        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { MediaCrypto.decrypt(key, tampered) }
        assertThrows(Exception::class.java) { MediaCrypto.decrypt(MediaCrypto.newKey(), blob) }
    }

    @Test
    fun sealedWakeOnlyOpensForItsRecipient() {
        val alice = Hpke.generate()
        val mallory = Hpke.generate()
        val wake = SealedWake(from = "Bob", sound = "preset:chime", image = "custom:3:${"a".repeat(64)}", imageKey = "k", message = "일어나! 9시 수업")

        val sealed = SealedWake.seal(wake, Hpke.exportPublic(alice))
        assertEquals(wake, SealedWake.open(sealed) { Hpke.decrypt(alice, it) })
        assertNull(SealedWake.open(sealed) { Hpke.decrypt(mallory, it) })
        assertNull(SealedWake.open("not base64!") { Hpke.decrypt(alice, it) })
    }

    @Test
    fun fullestWakeFitsTheServersSealedLimit() {
        val key = Hpke.exportPublic(Hpke.generate())
        val media = "custom:123456:${"a".repeat(64)}"
        val contentKey = ByteArray(32).toB64()
        val wake = SealedWake(
            from = "가".repeat(40), // MAX_DISPLAY_NAME_LENGTH
            sound = media, soundKey = contentKey,
            image = media, imageKey = contentKey,
            message = "가".repeat(SealedWake.MAX_MESSAGE_LENGTH),
        )
        // maxSealedLen in server/internal/api/trigger.go
        assertTrue(SealedWake.seal(wake, key).length <= 3000)
    }

    @Test
    fun publicKeyExportHasNoSecret() {
        val handle = Hpke.generate()
        val pub = Hpke.exportPublic(handle)
        // Usable for encryption by anyone holding it...
        val ct = Hpke.encrypt(pub, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), Hpke.decrypt(handle, ct))
        // ...but a different context string doesn't decrypt.
        assertThrows(Exception::class.java) { Hpke.decrypt(handle, ct, "other".toByteArray()) }
    }
}
