package app.wakelink.android.contacts

/** One line describing who can wake whom in a pairing, from this user's side. */
fun pairingSummary(iCanWakeThem: Boolean, theyCanWakeMe: Boolean): String = when {
    iCanWakeThem && theyCanWakeMe -> "서로 깨울 수 있어요"
    iCanWakeThem -> "나만 깨울 수 있어요 · 상대가 나를 깨우는 건 꺼져 있어요"
    theyCanWakeMe -> "상대만 나를 깨울 수 있어요 · 내 깨우기는 꺼져 있어요"
    else -> "둘 다 깨우기가 꺼져 있어요"
}
