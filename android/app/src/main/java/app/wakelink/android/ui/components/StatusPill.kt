package app.wakelink.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.wakelink.android.ui.theme.WakeTheme

enum class StatusKind { PROGRESS, SUCCESS, WARNING, ERROR, INFO }

/**
 * A short state line with an icon and tinted background, so "still
 * waiting", "it rang" and "it failed" are distinguishable at a glance and
 * not only by reading the words.
 */
@Composable
fun StatusPill(kind: StatusKind, text: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val status = WakeTheme.status
    val (container: Color, content: Color) = when (kind) {
        StatusKind.PROGRESS -> scheme.primaryContainer to scheme.onPrimaryContainer
        StatusKind.SUCCESS -> status.successContainer to status.onSuccessContainer
        StatusKind.WARNING -> status.warningContainer to status.onWarningContainer
        StatusKind.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        StatusKind.INFO -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .background(container, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (kind == StatusKind.PROGRESS) {
            CircularProgressIndicator(color = content, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            Icon(
                imageVector = when (kind) {
                    StatusKind.SUCCESS -> Icons.Rounded.CheckCircle
                    StatusKind.WARNING -> Icons.Rounded.Schedule
                    StatusKind.ERROR -> Icons.Rounded.ErrorOutline
                    else -> Icons.Rounded.Info
                },
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(text, color = content, style = MaterialTheme.typography.bodyMedium)
    }
}
