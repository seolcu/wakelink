package app.wakelink.android.contacts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import app.wakelink.android.crypto.WakeComposer
import app.wakelink.android.data.ArmedSync
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.images.ImagePickerSheet
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.MediaView
import app.wakelink.android.network.PairingView
import app.wakelink.android.network.PatchPairingRequest
import app.wakelink.android.network.TriggerView
import app.wakelink.android.network.WakeLinkApi
import app.wakelink.android.network.orThrow
import app.wakelink.android.network.toUserMessage
import app.wakelink.android.sounds.SoundPickerSheet
import app.wakelink.android.sounds.SoundRef
import app.wakelink.android.sounds.parseSoundRef
import app.wakelink.android.sounds.soundLabel
import app.wakelink.android.status.DeviceReadiness
import app.wakelink.android.ui.OnResume
import app.wakelink.android.ui.components.Avatar
import app.wakelink.android.ui.components.StatusKind
import app.wakelink.android.ui.components.StatusPill
import app.wakelink.android.ui.components.SunriseMark
import app.wakelink.android.ui.theme.WakeTheme
import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.HttpException

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

private const val DISMISS_POLL_INTERVAL_MS = 5_000L
private const val DISMISS_POLL_ATTEMPTS = 72 // ~6 min: the alarm auto-stops after 5

/**
 * After it rang, keeps checking whether the receiver stopped the alarm
 * themselves. Returns when they did (epoch seconds), or null once the alarm
 * must have auto-stopped. Runs only while the screen is shown.
 */
private suspend fun awaitDismiss(api: WakeLinkApi, pairingId: Long, triggerId: Long): Long? {
    repeat(DISMISS_POLL_ATTEMPTS) {
        delay(DISMISS_POLL_INTERVAL_MS)
        val triggers = runCatching { api.listTriggers(pairingId, limit = 10) }.getOrNull() ?: return@repeat
        triggers.firstOrNull { it.id == triggerId }?.dismissedAt?.let { return it }
    }
    return null
}

private fun dismissedStatus(context: Context, name: String, at: Long) = WakeStatus(
    StatusKind.SUCCESS,
    context.getString(
        R.string.contacts_dismissed,
        name,
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(at).atZone(ZoneId.systemDefault())),
    ),
)

/** Readiness problems already shown in a dialog during this process's lifetime. */
private val warningsAlreadyShown = mutableSetOf<String>()

private data class WakeStatus(val kind: StatusKind, val text: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(onOpenPairing: () -> Unit, onOpenSettings: () -> Unit, onOpenSetup: () -> Unit, onAccountLost: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokenStore = remember { TokenStore(context) }
    val api = remember { ApiClient.create(tokenStore) }

    var pairings by remember { mutableStateOf<List<PairingView>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val warnings = remember { mutableStateListOf<String>() }
    var popupWarnings by remember { mutableStateOf<List<String>>(emptyList()) }
    val settings = remember { SettingsRepository(context) }
    val armed by settings.armed.collectAsState(initial = true)

    var accountLost by remember { mutableStateOf(false) }

    /** [silent]: refresh in place (on resume) without the pull-to-refresh spinner. */
    suspend fun reload(silent: Boolean = false) {
        if (!silent) loading = true
        try {
            pairings = api.listPairings()
            loadError = null
        } catch (e: Exception) {
            // The server no longer knows this API key (account deleted, or the
            // server's data was reset): retrying can't help, so offer a way out.
            if (e is HttpException && e.code() == 401) accountLost = true
            loadError = context.getString(R.string.contacts_load_failed, e.toUserMessage(context))
        } finally {
            loading = false
        }
    }

    OnResume {
        // Contacts change behind our back -- unpairing, permission and
        // "receiving" switches, a reinstalled phone's new key -- so refresh
        // whenever the screen comes back, not just once.
        scope.launch { reload(silent = pairings.isNotEmpty()) }
        scope.launch {
            val readiness = DeviceReadiness.check(context)
            warnings.clear()
            if (!readiness.notificationsEnabled) warnings += context.getString(R.string.contacts_warning_notifications)
            if (!readiness.fullScreenIntentAllowed) warnings += context.getString(R.string.contacts_warning_full_screen)
            if (!readiness.ignoringBatteryOptimizations) warnings += context.getString(R.string.contacts_warning_battery)
            if (!readiness.backgroundUnrestricted) warnings += context.getString(R.string.contacts_warning_background)
            if (SettingsRepository(context).dndBypassEnabled.first() && !readiness.dndAccessGranted) {
                warnings += context.getString(R.string.contacts_warning_dnd)
            }
            // A banner is easy to scroll past, so a newly appearing problem
            // also gets a dialog -- once per distinct problem per launch.
            val fresh = warnings.filterNot { it in warningsAlreadyShown }
            if (fresh.isNotEmpty()) {
                warningsAlreadyShown += fresh
                popupWarnings = warnings.toList()
            }
        }
    }

    if (accountLost) {
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.contacts_account_lost_title)) },
            text = { Text(stringResource(R.string.contacts_account_lost_body)) },
            confirmButton = {
                TextButton(onClick = {
                    accountLost = false
                    tokenStore.clear()
                    onAccountLost()
                }) { Text(stringResource(R.string.contacts_account_lost_register)) }
            },
            dismissButton = { TextButton(onClick = { accountLost = false }) { Text(stringResource(R.string.setup_later)) } },
        )
    }

    if (popupWarnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { popupWarnings = emptyList() },
            icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.contacts_problem_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    popupWarnings.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    popupWarnings = emptyList()
                    onOpenSetup()
                }) { Text(stringResource(R.string.contacts_problem_fix)) }
            },
            dismissButton = {
                TextButton(onClick = { popupWarnings = emptyList() }) { Text(stringResource(R.string.setup_later)) }
            },
        )
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
                // Whether contacts can wake this phone is always visible here
                // (it used to be an always-on notification).
                item(key = "armed") {
                    ArmedCard(armed) { value -> scope.launch { ArmedSync.set(context, value) } }
                }
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
private fun ArmedCard(armed: Boolean, onChange: (Boolean) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (armed) WakeTheme.status.successContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (armed) WakeTheme.status.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(if (armed) Icons.Rounded.Alarm else Icons.Rounded.AlarmOff, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(if (armed) R.string.contacts_armed_on else R.string.contacts_armed_off),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(if (armed) R.string.settings_armed_on else R.string.settings_armed_off),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = armed, onCheckedChange = onChange)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairingCard(pairing: PairingView, api: WakeLinkApi, myUserId: Long, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    // The sound this user sends to this contact; null lets the receiver's own sound play.
    val soundKey by settings.soundForPairing(pairing.id).collectAsState(initial = null)
    // The picture for the next wake only (like the message); null = none.
    // Uploaded pictures stay in the picker for quick reuse.
    var imageKey by rememberSaveable(pairing.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(pairing.id) { settings.forgetLegacyImageForPairing(pairing.id) }
    var pickingImage by remember { mutableStateOf(false) }
    // A note for the next wake only; cleared once it has been sent.
    var message by rememberSaveable(pairing.id) { mutableStateOf("") }
    var editingMessage by remember { mutableStateOf(false) }
    var mySounds by remember { mutableStateOf<List<MediaView>?>(null) }
    var pickingSound by remember { mutableStateOf(false) }
    // A recording/picture picked here may since have been deleted (or be
    // unusable on this phone); drop such a stale choice instead of showing it.
    LaunchedEffect(soundKey) {
        val ref = parseSoundRef(soundKey) as? SoundRef.Custom ?: return@LaunchedEffect
        val list = runCatching { api.listSounds() }.getOrNull() ?: return@LaunchedEffect
        mySounds = list
        if (list.none { it.id == ref.id }) settings.setSoundForPairing(pairing.id, null)
    }
    LaunchedEffect(imageKey) {
        val ref = parseSoundRef(imageKey) as? SoundRef.Custom ?: return@LaunchedEffect
        val list = runCatching { api.listImages() }.getOrNull() ?: return@LaunchedEffect
        if (list.none { it.id == ref.id }) imageKey = null
    }
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
                    if (pairing.theyCanWakeMe && !pairing.iAcceptFromThem) {
                        Text(
                            stringResource(R.string.contacts_not_accepting_from, pairing.otherUserName),
                            style = MaterialTheme.typography.bodySmall,
                            color = WakeTheme.status.warning,
                        )
                    }
                    if (pairing.iCanWakeThem && !pairing.theyAcceptWakes) {
                        Text(
                            stringResource(R.string.contacts_they_paused, pairing.otherUserName),
                            style = MaterialTheme.typography.bodySmall,
                            color = WakeTheme.status.warning,
                        )
                    }
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
                            text = {
                                Text(
                                    stringResource(
                                        if (pairing.iAcceptFromThem) R.string.contacts_accept_from_off else R.string.contacts_accept_from_on,
                                        pairing.otherUserName,
                                    )
                                )
                            },
                            leadingIcon = {
                                Icon(if (pairing.iAcceptFromThem) Icons.Rounded.DoNotDisturbOn else Icons.Rounded.NotificationsActive, null)
                            },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    try {
                                        api.patchPairing(pairing.id, PatchPairingRequest(iAcceptFromThem = !pairing.iAcceptFromThem)).orThrow()
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
                enabled = pairing.iCanWakeThem && pairing.theyAcceptWakes && !waking,
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
                                api.trigger(pairing.id, WakeComposer.request(context, api, pairing, soundKey, imageKey, message)).triggerId
                            } catch (e: Exception) {
                                wakeStatus = WakeStatus(StatusKind.ERROR, context.getString(R.string.contacts_send_failed, e.toUserMessage(context)))
                                // e.g. unpaired, permission revoked or receiving switched off
                                // meanwhile: show the current state rather than the stale card.
                                onChanged()
                                return@launch
                            }
                            message = ""
                            imageKey = null
                            // Sent is not rang: keep the user waiting on the
                            // receiver's ack before claiming success.
                            wakeStatus = WakeStatus(StatusKind.PROGRESS, context.getString(R.string.contacts_checking, pairing.otherUserName))
                            val check = awaitAck(api, pairing.id, triggerId)
                            val ringing = WakeStatus(StatusKind.SUCCESS, context.getString(R.string.contacts_ringing, pairing.otherUserName))
                            wakeStatus = when (check) {
                                AckCheck.ACKED, AckCheck.DISMISSED -> ringing
                                AckCheck.FAILED -> WakeStatus(StatusKind.ERROR, context.getString(R.string.contacts_push_failed))
                                AckCheck.PENDING -> WakeStatus(
                                    StatusKind.WARNING,
                                    context.getString(R.string.contacts_unconfirmed),
                                )
                            }
                            if (showHistory) loadHistory()
                            waking = false
                            if (check == AckCheck.ACKED || check == AckCheck.DISMISSED) {
                                // Rang; now wait (screen open only) for "they stopped it".
                                val at = awaitDismiss(api, pairing.id, triggerId)
                                if (at != null && wakeStatus === ringing) { // not replaced by a newer wake
                                    wakeStatus = dismissedStatus(context, pairing.otherUserName, at)
                                    if (showHistory) loadHistory()
                                }
                            }
                        } finally {
                            waking = false
                        }
                    }
                },
            ) {
                Icon(Icons.Rounded.Alarm, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(
                        when {
                            !pairing.iCanWakeThem -> R.string.contacts_wake_off
                            !pairing.theyAcceptWakes -> R.string.contacts_wake_paused
                            else -> R.string.contacts_wake
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (pairing.iCanWakeThem) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { pickingSound = true },
                        leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = { Text(stringResource(R.string.contacts_sound, soundLabel(soundKey, mySounds))) },
                    )
                    AssistChip(
                        onClick = { pickingImage = true },
                        leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = {
                            Text(stringResource(if (imageKey == null) R.string.contacts_image_none else R.string.contacts_image_set))
                        },
                    )
                    AssistChip(
                        onClick = { editingMessage = true },
                        leadingIcon = { Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = {
                            Text(
                                if (message.isEmpty()) stringResource(R.string.contacts_message_none)
                                else stringResource(R.string.contacts_message_set, message),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp),
                            )
                        },
                    )
                }
                if (pairing.otherPublicKey == null && (soundKey != null || imageKey != null || message.isNotEmpty())) {
                    Text(
                        stringResource(R.string.contacts_extras_need_update, pairing.otherUserName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

    if (pickingSound) {
        SoundPickerSheet(
            title = stringResource(R.string.contacts_sound_title, pairing.otherUserName),
            selected = soundKey,
            onSelect = { key -> scope.launch { settings.setSoundForPairing(pairing.id, key) } },
            onDismiss = {
                pickingSound = false
                scope.launch { mySounds = runCatching { api.listSounds() }.getOrNull() }
            },
            api = api,
        )
    }

    if (editingMessage) {
        MessageDialog(
            name = pairing.otherUserName,
            initial = message,
            onSave = {
                message = it
                editingMessage = false
            },
            onDismiss = { editingMessage = false },
        )
    }

    if (pickingImage) {
        ImagePickerSheet(
            title = stringResource(R.string.contacts_image_title, pairing.otherUserName),
            selected = imageKey,
            api = api,
            onSelect = { key -> imageKey = key },
            onDismiss = { pickingImage = false },
        )
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
