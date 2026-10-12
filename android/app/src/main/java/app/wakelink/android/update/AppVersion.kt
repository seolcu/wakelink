package app.wakelink.android.update

/**
 * Parses a release tag or versionName like "v0.2.0" / "0.2.0" into its
 * numeric parts. Anything that isn't plain dotted numbers (e.g. "0.2.0-rc1")
 * is rejected, so a pre-release tag can never be offered as an update.
 */
fun parseVersion(raw: String): List<Int>? {
    val parts = raw.trim().removePrefix("v").removePrefix("V").split(".")
    if (parts.isEmpty()) return null
    return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
}

/** True when [remote] is a strictly newer version than [current]; false if either can't be parsed. */
fun isNewerVersion(remote: String, current: String): Boolean {
    val r = parseVersion(remote) ?: return false
    val c = parseVersion(current) ?: return false
    for (i in 0 until maxOf(r.size, c.size)) {
        val diff = r.getOrElse(i) { 0 } - c.getOrElse(i) { 0 }
        if (diff != 0) return diff > 0
    }
    return false
}
