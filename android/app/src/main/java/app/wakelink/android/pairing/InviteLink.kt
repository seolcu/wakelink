package app.wakelink.android.pairing

private const val INVITE_LINK_PREFIX = "wakelink://invite/"

// https://<server>/invite/CODE -- the shareable web link (server's
// internal/web invite page), which App Links route straight into the app.
private val WEB_INVITE_LINK = Regex("^https?://[^/?#]+/invite/", RegexOption.IGNORE_CASE)

/**
 * Extracts the invite code from a `wakelink://invite/CODE` link (the
 * server's `deep_link`) or an `https://<server>/invite/CODE` web link.
 * Returns null for anything else. Plain string parsing on purpose, so
 * it's unit-testable without android.net.Uri.
 */
fun parseInviteCode(link: String?): String? {
    val raw = link?.trim() ?: return null
    val rest = when {
        raw.startsWith(INVITE_LINK_PREFIX, ignoreCase = true) -> raw.substring(INVITE_LINK_PREFIX.length)
        else -> WEB_INVITE_LINK.find(raw)?.let { raw.substring(it.range.last + 1) } ?: return null
    }
    val code = normalizeInviteCode(rest.substringBefore('?').substringBefore('#'))
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

/** The shareable https invite link for [code] on the server at [serverBaseUrl]. */
fun inviteWebLink(serverBaseUrl: String, code: String): String = serverBaseUrl.trimEnd('/') + "/invite/" + code

fun inviteShareText(code: String, webLink: String): String =
    "WakeLink 초대 코드: $code\n" +
        "이 링크를 누르면 앱이 열리고, 앱이 없으면 설치 방법이 나와요: $webLink\n" +
        "(코드는 잠시 후 만료돼요)"
