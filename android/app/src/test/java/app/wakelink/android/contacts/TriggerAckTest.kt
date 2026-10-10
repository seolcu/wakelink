package app.wakelink.android.contacts

import app.wakelink.android.network.TriggerView
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerAckTest {
    private fun trigger(id: Long, status: String = "sent", ack: Long? = null, from: Long = 1) = TriggerView(
        id = id, pairingId = 7, fromUserId = from, toUserId = if (from == 1L) 2 else 1,
        createdAt = 1_700_000_000, sendStatus = status, ackReceivedAt = ack,
    )

    @Test
    fun sentIsNotRang() {
        assertEquals(AckCheck.PENDING, ackCheckFor(5, listOf(trigger(5))))
    }

    @Test
    fun ackMeansRang() {
        assertEquals(AckCheck.ACKED, ackCheckFor(5, listOf(trigger(4, ack = 1), trigger(5, ack = 1_700_000_003))))
    }

    @Test
    fun fcmErrorIsFailure() {
        assertEquals(AckCheck.FAILED, ackCheckFor(5, listOf(trigger(5, status = "fcm_error"))))
    }

    @Test
    fun missingTriggerKeepsWaiting() {
        assertEquals(AckCheck.PENDING, ackCheckFor(5, listOf(trigger(4, ack = 1))))
        assertEquals(AckCheck.PENDING, ackCheckFor(5, emptyList()))
    }

    @Test
    fun historyLineIsFromMyPointOfView() {
        // 1_700_000_000 = 2023-11-14 22:13:20 UTC
        assertEquals("11/14 22:13 · 내가 깨움 · 울림 확인됨", historyLine(trigger(1, ack = 1), myUserId = 1, zone = ZoneOffset.UTC))
        assertEquals("11/14 22:13 · 상대가 나를 깨움 · 울림 미확인", historyLine(trigger(1, from = 2), myUserId = 1, zone = ZoneOffset.UTC))
        assertEquals("11/14 22:13 · 내가 깨움 · 전달 실패", historyLine(trigger(1, status = "fcm_error"), myUserId = 1, zone = ZoneOffset.UTC))
    }
}
