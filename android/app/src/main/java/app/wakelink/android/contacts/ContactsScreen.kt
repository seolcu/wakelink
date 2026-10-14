package app.wakelink.android.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.wakelink.android.R
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.PairingView
import app.wakelink.android.network.PatchPairingRequest
import app.wakelink.android.network.TriggerView
import app.wakelink.android.network.WakeLinkApi
import app.wakelink.android.network.orThrow
import app.wakelink.android.network.toUserMessage
import app.wakelink.android.status.DeviceReadiness
import app.wakelink.android.ui.OnResume
import app.wakelink.android.ui.components.Avatar
import app.wakelink.android.ui.components.StatusKind
import app.wakelink.android.ui.components.StatusPill
import app.wakelink.android.ui.components.SunriseMark
import app.wakelink.android.ui.theme.WakeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val ACK_POLL_INTERVAL_MS = 2_000L
private const val ACK_POLL_ATTEMPTS = 30 // ~60s: long enough for a dozing phone to wake on a high-priority push

/**
 * Polls the trigger list until the receiver acks [triggerId], FCM reports
 * failure, or we give up. Transient network errors while polling don't end
 * the wait -- the push may well have gone through.
 */
private suspend fun awaitAck(api: WakeLinkApi, pairingId: Long, triggerId: Long): AckCheck {
    repeat(ACK_POLL_ATTEMPTS) {
        delay(ACK_POLL_INTERVAL_MS)
        val triggers = runCatching { api.listTriggers(pairingId, limit = 10) }.getOrNull() ?: return@repeat
        val check = ackCheckFor(triggerId, triggers)
        if (check != AckCheck.PENDING) return check
    }
    return AckCheck.PENDING
}

private data class WakeStatus(val kind: StatusKind, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onOpenPairing: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { TokenStore(context) }
    val api = remember { ApiClient.create(tokenStore) }

    var pairings by remember { mutableStateOf<List<PairingView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val warnings = remember { mutableStateListOf<String>() }

    suspend fun reload() {
        loading = true
        try {
            pairings = api.listPairings()
            loadError = null
        } catch (e: Exception) {
            loadError = context.getString(R.string.contacts_load_failed, e.toUserMessage(context))
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    OnResume {
        scope.launch {
            val readiness = DeviceReadiness.check(context)
            val armed = SettingsRepository(context).armed.first()
            warnings.clear()
            if (!armed) warnings += context.getString(R.string.contacts_warning_disarmed)
            if (!readiness.notificationsEnabled) warnings += context.getString(R.string.contacts_warning_notifications)
            if (!readiness.fullScreenIntentAllowed) warnings += context.getString(R.string.contacts_warning_full_screen)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("WakeLink", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { scope.launch { reload() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.contacts_refresh))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenPairing,
                icon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
                text = { Text(stringResource(R.string.contacts_add)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = loading && pairings.isNotEmpty(),
            onRefresh = { scope.launch { reload() } },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (warnings.isNotEmpty()) {
                    item(key = "warnings") { WarningsBanner(warnings, onOpenSettings) }
                }
                loadError?.let { err ->
                    item(key = "error") { StatusPill(StatusKind.ERROR, err, Modifier.fillMaxWidth()) }
                }
                when {
                    loading && pairings.isEmpty() -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    pairings.isEmpty() && loadError == null -> item(key = "empty") { EmptyState(onOpenPairing) }
                    else -> items(pairings, key = { it.id }) { pairing ->
                        PairingCard(
                            pairing = pairing,
                            api = api,
                            myUserId = tokenStore.userId,
                            onChanged = { scope.launch { reload() } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningsBanner(warnings: List<String>, onOpenSettings: () -> Unit) {
    Card(
        onClick = onOpenSettings,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                warnings.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.contacts_warning_tap), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EmptyState(onOpenPairing: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 72.dp, start = 16.dp, end = 16.dp),
    ) {
        SunriseMark(size = 96.dp, sun = MaterialTheme.colorScheme.secondary)
        Text(stringResource(R.string.contacts_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.contacts_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onOpenPairing, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.contacts_add_first))
        }
    }
}

@Composable
private fun PairingCard(pairing: PairingView, api: WakeLinkApi, myUserId: Long, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wakeStatus by remember { mutableStateOf<WakeStatus?>(null) }
    var waking by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<TriggerView>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }

    suspend fun loadHistory() {
        history = runCatching { api.listTriggers(pairing.id, limit = 10) }.getOrNull()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(pairing.otherUserName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(pairing.otherUserName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(pairingSummary(pairing.iCanWakeThem, pairing.theyCanWakeMe)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.contacts_menu, pairing.otherUserName))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (pairing.iCanWakeThem) R.string.contacts_my_wake_off else R.string.contacts_my_wake_on)) },
                            leadingIcon = {
                                Icon(if (pairing.iCanWakeThem) Icons.Rounded.NotificationsOff else Icons.Rounded.Alarm, null)
                            },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    try {
                                        api.patchPairing(pairing.id, PatchPairingRequest(iCanWakeThem = !pairing.iCanWakeThem)).orThrow()
                                    } catch (e: Exception) {
                                        wakeStatus = WakeStatus(StatusKind.ERROR, context.getString(R.string.contacts_setting_failed, e.toUserMessage(context)))
                                    }
                                    onChanged()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.contacts_unpair), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.LinkOff, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                confirmUnpair = true
                            },
                        )
                    }
                }
            }

            Button(
                enabled = pairing.iCanWakeThem && !waking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                onClick = {
                    waking = true
                    wakeStatus = WakeStatus(StatusKind.PROGRESS, context.getString(R.string.contacts_sending))
                    scope.launch {
                        try {
                            val triggerId = try {
                                api.trigger(pairing.id).triggerId
                            } catch (e: Exception) {
                                wakeStatus = WakeStatus(StatusKind.ERROR, context.getString(R.string.contacts_send_failed, e.toUserMessage(context)))
                                return@launch
                            }
                            // Sent is not rang: keep the user waiting on the
                            // receiver's ack before claiming success.
                            wakeStatus = WakeStatus(StatusKind.PROGRESS, context.getString(R.string.contacts_checking, pairing.otherUserName))
                            wakeStatus = when (awaitAck(api, pairing.id, triggerId)) {
                                AckCheck.ACKED -> WakeStatus(StatusKind.SUCCESS, context.getString(R.string.contacts_ringing, pairing.otherUserName))
                                AckCheck.FAILED -> WakeStatus(StatusKind.ERROR, context.getString(R.string.contacts_push_failed))
                                AckCheck.PENDING -> WakeStatus(
                                    StatusKind.WARNING,
                                    context.getString(R.string.contacts_unconfirmed),
                                )
                            }
                            if (showHistory) loadHistory()
                        } finally {
                            waking = false
                        }
                    }
                },
            ) {
                Icon(Icons.Rounded.Alarm, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (pairing.iCanWakeThem) R.string.contacts_wake else R.string.contacts_wake_off), style = MaterialTheme.typography.titleMedium)
            }

            wakeStatus?.let { StatusPill(it.kind, it.text, Modifier.fillMaxWidth()) }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showHistory = !showHistory
                        if (showHistory) scope.launch { loadHistory() }
                    }
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    stringResource(R.string.contacts_history),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (showHistory) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(if (showHistory) R.string.contacts_history_hide else R.string.contacts_history_show),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = showHistory) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (val h = history) {
                        null -> Text(stringResource(R.string.common_loading), style = MaterialTheme.typography.bodySmall)
                        else -> if (h.isEmpty()) {
                            Text(
                                stringResource(R.string.contacts_history_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            h.forEach { HistoryRow(it, myUserId) }
                        }
                    }
                }
            }
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            icon = { Icon(Icons.Rounded.LinkOff, contentDescription = null) },
            title = { Text(stringResource(R.string.contacts_unpair_title, pairing.otherUserName)) },
            text = { Text(stringResource(R.string.contacts_unpair_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    scope.launch {
                        try {
                            api.deletePairing(pairing.id).orThrow()
                        } catch (e: Exception) {
                            wakeStatus = WakeStatus(StatusKind.ERROR, context.getString(R.string.contacts_unpair_failed, e.toUserMessage(context)))
                        }
                        onChanged()
                    }
                }) { Text(stringResource(R.string.contacts_unpair), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun HistoryRow(t: TriggerView, myUserId: Long) {
    val outgoing = t.fromUserId == myUserId
    val tint = when {
        t.ackReceivedAt != null -> WakeTheme.status.success
        t.sendStatus != "sent" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(
            if (outgoing) Icons.AutoMirrored.Rounded.CallMade else Icons.AutoMirrored.Rounded.CallReceived,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        val line = historyLine(t, myUserId)
        Text(
            stringResource(R.string.history_line, line.time, stringResource(line.direction), stringResource(line.outcome)),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
