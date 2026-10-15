package app.wakelink.android.contacts

import androidx.annotation.StringRes
import app.wakelink.android.R
import app.wakelink.android.network.TriggerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** PENDING -> ACKED (it rang) -> DISMISSED (they stopped it themselves), or FAILED. */
enum class AckCheck { ACKED, DISMISSED, PENDING, FAILED }

/**
 * Where a just-sent trigger stands, judged from a fresh trigger list. Only
 * an ack from the receiving device counts as "it rang" -- a "sent" status
 * only means FCM accepted the push.
 */
fun ackCheckFor(triggerId: Long, triggers: List<TriggerView>): AckCheck {
    val t = triggers.firstOrNull { it.id == triggerId } ?: return AckCheck.PENDING
    return when {
        t.dismissedAt != null -> AckCheck.DISMISSED
        t.ackReceivedAt != null -> AckCheck.ACKED
        t.sendStatus != "sent" -> AckCheck.FAILED
        else -> AckCheck.PENDING
    }
}

private val historyTimeFormat = DateTimeFormatter.ofPattern("M/d HH:mm")

/** One line of a pairing's trigger history, from this user's point of view. */
data class HistoryLine(val time: String, @StringRes val direction: Int, @StringRes val outcome: Int)

fun historyLine(t: TriggerView, myUserId: Long, zone: ZoneId = ZoneId.systemDefault()): HistoryLine {
    val time = historyTimeFormat.format(Instant.ofEpochSecond(t.createdAt).atZone(zone))
    val direction = if (t.fromUserId == myUserId) R.string.history_outgoing else R.string.history_incoming
    val outcome = when {
        t.dismissedAt != null -> R.string.history_dismissed
        t.ackReceivedAt != null -> R.string.history_acked
        t.sendStatus != "sent" -> R.string.history_failed
        else -> R.string.history_unconfirmed
    }
    return HistoryLine(time, direction, outcome)
}
