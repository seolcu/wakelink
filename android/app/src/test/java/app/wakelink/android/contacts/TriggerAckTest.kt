package app.wakelink.android.contacts

import app.wakelink.android.R
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
        assertEquals(
            HistoryLine("11/14 22:13", R.string.history_outgoing, R.string.history_acked),
            historyLine(trigger(1, ack = 1), myUserId = 1, zone = ZoneOffset.UTC),
        )
        assertEquals(
            HistoryLine("11/14 22:13", R.string.history_incoming, R.string.history_unconfirmed),
            historyLine(trigger(1, from = 2), myUserId = 1, zone = ZoneOffset.UTC),
        )
        assertEquals(
            HistoryLine("11/14 22:13", R.string.history_outgoing, R.string.history_failed),
            historyLine(trigger(1, status = "fcm_error"), myUserId = 1, zone = ZoneOffset.UTC),
        )
    }
}
