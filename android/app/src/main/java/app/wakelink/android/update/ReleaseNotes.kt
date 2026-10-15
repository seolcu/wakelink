package app.wakelink.android.update

/**
 * GitHub release notes, split by language and parsed into the few markdown
 * constructs our releases use, so the update dialog can show them as text
 * instead of raw `###`/`**`. Release notes are written as a `## English`
 * section followed by `## 한국어` (see CLAUDE.md); anything else is one
 * language-less section. Pure Kotlin, unit-tested.
 */
object ReleaseNotes {
    const val ENGLISH = "en"
    const val KOREAN = "ko"

    sealed interface Block {
        data class Heading(val text: String) : Block
        data class Bullet(val text: String) : Block
        data class Quote(val text: String) : Block
        data class Paragraph(val text: String) : Block
    }

    /** Language code -> blocks, in the order they appear. Key null when the notes aren't split by language. */
    fun parse(markdown: String): Map<String?, List<Block>> {
        val sections = linkedMapOf<String?, MutableList<String>>()
        var current: String? = null
        for (line in markdown.lines()) {
            val lang = languageOfHeading(line)
            if (lang != null) {
                current = lang
                sections.getOrPut(lang) { mutableListOf() }
                continue
            }
            sections.getOrPut(current) { mutableListOf() }.add(line)
        }
        return sections
            .mapValues { (_, lines) -> blocks(lines) }
            .filterValues { it.isNotEmpty() }
    }

    /** Which section to show first: the app's language if present, else English, else whatever exists. */
    fun defaultLanguage(available: Collection<String?>, appLanguage: String): String? = when {
        appLanguage in available -> appLanguage
        ENGLISH in available -> ENGLISH
        else -> available.firstOrNull()
    }

    private fun languageOfHeading(line: String): String? {
        val t = line.trim()
        if (!t.startsWith("## ") || t.startsWith("###")) return null
        return when (t.removePrefix("## ").trim().lowercase()) {
            "english" -> ENGLISH
            "한국어", "korean" -> KOREAN
            else -> null
        }
    }

    private val ORDERED = Regex("^\\d+[.)]\\s+")

    private fun blocks(lines: List<String>): List<Block> {
        val out = mutableListOf<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) out += Block.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> flush()
                line.startsWith("#") -> {
                    flush()
                    line.trimStart('#').trim().takeIf { it.isNotEmpty() }?.let { out += Block.Heading(it) }
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flush()
                    out += Block.Bullet(line.substring(2).trim())
                }
                ORDERED.containsMatchIn(line) -> {
                    flush()
                    out += Block.Bullet(line.replaceFirst(ORDERED, ""))
                }
                line.startsWith(">") -> {
                    flush()
                    line.removePrefix(">").trim().takeIf { it.isNotEmpty() }?.let { out += Block.Quote(it) }
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line)
                }
            }
        }
        flush()
        return out
    }

    /** A run of inline text: plain, or `**bold**` (also used for `code`). */
    data class Span(val text: String, val bold: Boolean)

    private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\([^)]*\)""")

    /** Splits inline markdown into spans; links keep only their text. */
    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var last = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > last) out += Span(text.substring(last, m.range.first), bold = false)
            when {
                m.groups[1] != null -> out += Span(m.groupValues[1], bold = true)
                m.groups[2] != null -> out += Span(m.groupValues[2], bold = true)
                else -> out += Span(m.groupValues[3], bold = false)
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += Span(text.substring(last), bold = false)
        return out
    }
}
