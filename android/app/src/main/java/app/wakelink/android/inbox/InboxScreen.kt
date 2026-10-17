package app.wakelink.android.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wakelink.android.R
import app.wakelink.android.crypto.fromB64
import app.wakelink.android.data.ReceivedWake
import app.wakelink.android.data.ReceivedWakes
import app.wakelink.android.images.ImageFileView
import app.wakelink.android.sounds.ImageCache
import app.wakelink.android.sounds.SoundRef
import app.wakelink.android.sounds.parseSoundRef
import app.wakelink.android.ui.components.Avatar
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private const val IMAGE_TIMEOUT_MS = 30_000L

/** Messages and pictures that came with wakes, to look at after the alarm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ReceivedWakes(context) }
    val wakes by store.all.collectAsState(initial = emptyList())
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(wakes) {
        store.markSeen()
        WakeMessageNotifier.cancelAll(context)
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.inbox_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { store.clear() }
                }) { Text(stringResource(R.string.inbox_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                actions = {
                    if (wakes.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.inbox_clear))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        if (wakes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.inbox_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            items(wakes, key = { it.triggerId }) { wake ->
                ReceivedWakeCard(wake, onDelete = { scope.launch { store.remove(wake.triggerId) } })
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
private fun ReceivedWakeCard(wake: ReceivedWake, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(wake.from, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(wake.from, style = MaterialTheme.typography.titleSmall)
                    Text(
                        timeFormat.format(Instant.ofEpochMilli(wake.receivedAt).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.inbox_delete))
                }
            }
            wake.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            (parseSoundRef(wake.image) as? SoundRef.Custom)?.let { ref -> ReceivedImage(ref, wake.imageKey) }
        }
    }
}

/** The cached picture, or a fresh download if the cache has since let it go. */
@Composable
private fun ReceivedImage(ref: SoundRef.Custom, imageKey: String?) {
    val context = LocalContext.current
    // Unit = still loading, null = unavailable.
    val file by produceState<Any?>(Unit, ref) {
        value = ImageCache.obtain(context, ref, IMAGE_TIMEOUT_MS, imageKey?.fromB64())
    }
    when (val f = file) {
        is File -> ImageFileView(f, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)))
        Unit -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> Text(
            stringResource(R.string.inbox_image_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
