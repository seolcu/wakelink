package app.wakelink.android.contacts

import androidx.annotation.StringRes
import app.wakelink.android.R

/** One line describing who can wake whom in a pairing, from this user's side. */
@StringRes
fun pairingSummary(iCanWakeThem: Boolean, theyCanWakeMe: Boolean): Int = when {
    iCanWakeThem && theyCanWakeMe -> R.string.summary_both
    iCanWakeThem -> R.string.summary_only_me
    theyCanWakeMe -> R.string.summary_only_them
    else -> R.string.summary_neither
}
