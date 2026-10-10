package app.wakelink.android.contacts

import app.wakelink.android.network.TriggerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class AckCheck { ACKED, PENDING, FAILED }

/**
 * Where a just-sent trigger stands, judged from a fresh trigger list. Only
 * an ack from the receiving device counts as "it rang" -- a "sent" status
 * only means FCM accepted the push.
 */
fun ackCheckFor(triggerId: Long, triggers: List<TriggerView>): AckCheck {
    val t = triggers.firstOrNull { it.id == triggerId } ?: return AckCheck.PENDING
    return when {
        t.ackReceivedAt != null -> AckCheck.ACKED
        t.sendStatus != "sent" -> AckCheck.FAILED
        else -> AckCheck.PENDING
    }
}

private val historyTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

/** One line of a pairing's trigger history, from this user's point of view. */
fun historyLine(t: TriggerView, myUserId: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = historyTimeFormat.format(Instant.ofEpochSecond(t.createdAt).atZone(zone))
    val direction = if (t.fromUserId == myUserId) "내가 깨움" else "상대가 나를 깨움"
    val outcome = when {
        t.ackReceivedAt != null -> "울림 확인됨"
        t.sendStatus != "sent" -> "전달 실패"
        else -> "울림 미확인"
    }
    return "$time · $direction · $outcome"
}
