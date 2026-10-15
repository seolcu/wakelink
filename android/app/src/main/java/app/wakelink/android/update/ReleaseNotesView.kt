package app.wakelink.android.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.wakelink.android.update.ReleaseNotes.Block

/**
 * Release notes rendered from their markdown, showing the section in the
 * app's language first with a toggle to the other one.
 */
@Composable
fun ReleaseNotesView(markdown: String, modifier: Modifier = Modifier) {
    val sections = remember(markdown) { ReleaseNotes.parse(markdown) }
    if (sections.isEmpty()) return
    val appLanguage = LocalConfiguration.current.locales[0].language
    var language by rememberSaveable(markdown) { mutableStateOf(ReleaseNotes.defaultLanguage(sections.keys, appLanguage)) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val languages = sections.keys.filterNotNull()
        if (languages.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.forEach { lang ->
                    FilterChip(
                        selected = language == lang,
                        onClick = { language = lang },
                        label = { Text(if (lang == ReleaseNotes.KOREAN) "한국어" else "English") },
                    )
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
        ) {
            sections[language].orEmpty().forEach { block ->
                when (block) {
                    is Block.Heading -> Text(
                        inline(block.text),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is Block.Bullet -> Row {
                        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                        Text(inline(block.text), style = MaterialTheme.typography.bodyMedium)
                    }
                    is Block.Quote -> Text(
                        inline(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is Block.Paragraph -> Text(inline(block.text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    ReleaseNotes.spans(text).forEach { span ->
        if (span.bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) } else append(span.text)
    }
}
