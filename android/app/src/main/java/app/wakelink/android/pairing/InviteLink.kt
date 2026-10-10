package app.wakelink.android.pairing

private const val INVITE_LINK_PREFIX = "wakelink://invite/"

/**
 * Extracts the invite code from a `wakelink://invite/CODE` link (the
 * server's `deep_link`). Returns null for anything else. Plain string
 * parsing on purpose, so it's unit-testable without android.net.Uri.
 */
fun parseInviteCode(link: String?): String? {
    val raw = link?.trim() ?: return null
    if (!raw.startsWith(INVITE_LINK_PREFIX, ignoreCase = true)) return null
    val code = normalizeInviteCode(raw.substring(INVITE_LINK_PREFIX.length).substringBefore('?').substringBefore('#'))
    return code.ifEmpty { null }
}

/**
 * Cleans up whatever the user typed or pasted into the code field: a full
 * invite link is reduced to its code, and spaces/dashes people add when
 * reading a code aloud are dropped.
 */
fun normalizeInviteCode(input: String): String {
    parseInviteCode(input)?.let { return it }
    return input.uppercase().filter { it.isLetterOrDigit() }.take(16)
}

/** mm:ss, clamped at zero. */
fun formatRemaining(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun inviteShareText(code: String, deepLink: String): String =
    "WakeLink 초대 코드: $code\n" +
        "앱이 설치되어 있다면 이 링크로 바로 열 수 있어요: $deepLink\n" +
        "(코드는 잠시 후 만료돼요)"
