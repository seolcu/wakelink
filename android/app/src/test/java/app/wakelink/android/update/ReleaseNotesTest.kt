package app.wakelink.android.update

import app.wakelink.android.update.ReleaseNotes.Block
import app.wakelink.android.update.ReleaseNotes.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    private val bilingual = """
        ## English

        ### What's new
        - **Your sound.** Pick one.
        - Second item

        Some text
        that wraps.

        ## 한국어

        ### 바뀐 점
        1. **내 소리.** 골라요.
        > **주의:** 경고
    """.trimIndent()

    @Test
    fun splitsByLanguage() {
        val notes = ReleaseNotes.parse(bilingual)
        assertEquals(listOf("en", "ko"), notes.keys.toList())
        assertEquals(
            listOf(
                Block.Heading("What's new"),
                Block.Bullet("**Your sound.** Pick one."),
                Block.Bullet("Second item"),
                Block.Paragraph("Some text that wraps."),
            ),
            notes["en"],
        )
        assertEquals(
            listOf(Block.Heading("바뀐 점"), Block.Bullet("**내 소리.** 골라요."), Block.Quote("**주의:** 경고")),
            notes["ko"],
        )
    }

    @Test
    fun notesWithoutLanguageSectionsAreOneSection() {
        val notes = ReleaseNotes.parse("## Fixed\n- a crash")
        assertEquals(mapOf(null to listOf(Block.Heading("Fixed"), Block.Bullet("a crash"))), notes)
    }

    @Test
    fun defaultLanguageFollowsTheApp() {
        assertEquals("ko", ReleaseNotes.defaultLanguage(listOf("en", "ko"), "ko"))
        assertEquals("en", ReleaseNotes.defaultLanguage(listOf("en", "ko"), "en"))
        assertEquals("en", ReleaseNotes.defaultLanguage(listOf("en", "ko"), "ja"))
        assertEquals("ko", ReleaseNotes.defaultLanguage(listOf("ko"), "ja"))
        assertEquals(null, ReleaseNotes.defaultLanguage(listOf(null), "ko"))
    }

    @Test
    fun inlineSpans() {
        assertEquals(
            listOf(Span("A ", false), Span("bold", true), Span(" and ", false), Span("code", true), Span(" and ", false), Span("link", false)),
            ReleaseNotes.spans("A **bold** and `code` and [link](https://x)"),
        )
        assertEquals(listOf(Span("plain", false)), ReleaseNotes.spans("plain"))
    }
}
