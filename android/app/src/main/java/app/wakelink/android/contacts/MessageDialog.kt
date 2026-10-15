package app.wakelink.android.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.wakelink.android.R
import app.wakelink.android.crypto.SealedWake

/**
 * Edits the short note that goes with the next wake to [name]. It's sent
 * once and then cleared, unlike the sound and picture choices.
 */
@Composable
fun MessageDialog(name: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null) },
        title = { Text(stringResource(R.string.contacts_message_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace('\n', ' ').take(SealedWake.MAX_MESSAGE_LENGTH) },
                    placeholder = { Text(stringResource(R.string.contacts_message_placeholder)) },
                    supportingText = { Text("${text.length} / ${SealedWake.MAX_MESSAGE_LENGTH}") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(
                    stringResource(R.string.contacts_message_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.contacts_message_save)) }
        },
        dismissButton = {
            if (initial.isNotEmpty()) {
                TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.contacts_message_remove)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}
