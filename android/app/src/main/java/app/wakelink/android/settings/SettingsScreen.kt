package app.wakelink.android.settings

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneLocked
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.wakelink.android.BuildConfig
import app.wakelink.android.R
import app.wakelink.android.alarm.AlarmForegroundService
import app.wakelink.android.data.DismissMode
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.SoundPolicy
import app.wakelink.android.data.ThemeMode
import app.wakelink.android.data.TokenStore
import app.wakelink.android.network.ApiClient
import app.wakelink.android.network.PatchMeRequest
import app.wakelink.android.network.orThrow
import app.wakelink.android.network.toUserMessage
import app.wakelink.android.pairing.MAX_DISPLAY_NAME_LENGTH
import app.wakelink.android.sounds.SoundPickerSheet
import app.wakelink.android.sounds.SoundPreview
import app.wakelink.android.sounds.SoundRef
import app.wakelink.android.sounds.parseSoundRef
import app.wakelink.android.sounds.soundLabel
import app.wakelink.android.status.DeviceReadiness
import app.wakelink.android.ui.OnResume
import app.wakelink.android.ui.components.Avatar
import app.wakelink.android.ui.components.SectionCard
import app.wakelink.android.ui.components.SettingsRow
import app.wakelink.android.ui.components.StatusKind
import app.wakelink.android.ui.components.StatusPill
import app.wakelink.android.ui.theme.WakeTheme
import app.wakelink.android.update.AppUpdater
import app.wakelink.android.update.UpdateCheckWorker
import app.wakelink.android.update.UpdateDialog
import app.wakelink.android.update.UpdateInfo
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDone: () -> Unit, onAccountDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsRepository(context) }
    val tokenStore = remember { TokenStore(context) }
    val api = remember { ApiClient.create(tokenStore) }

    var armed by remember { mutableStateOf(true) }
    var dndBypass by remember { mutableStateOf(false) }
    var dismissMode by remember { mutableStateOf(DismissMode.UNLOCK_REQUIRED) }
    var volumePercent by remember { mutableIntStateOf(SettingsRepository.DEFAULT_VOLUME_PERCENT) }
    var crescendo by remember { mutableStateOf(false) }
    var defaultSound by remember { mutableStateOf(SettingsRepository.DEFAULT_SOUND_KEY) }
    var soundPolicy by remember { mutableStateOf(SoundPolicy.ALLOW_ALL) }
    var showImages by remember { mutableStateOf(true) }
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var pickingTheme by remember { mutableStateOf(false) }
    var pickingDefaultSound by remember { mutableStateOf(false) }
    var pickingPolicy by remember { mutableStateOf(false) }
    var dndAccessGranted by remember { mutableStateOf(false) }
    var readiness by remember { mutableStateOf<DeviceReadiness?>(null) }

    var myName by remember { mutableStateOf<String?>(null) }
    var accountError by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRotateKey by remember { mutableStateOf(false) }
    var keyRotated by remember { mutableStateOf(false) }

    var autoUpdateCheck by remember { mutableStateOf(true) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        armed = settings.armed.first()
        dndBypass = settings.dndBypassEnabled.first()
        dismissMode = settings.dismissMode.first()
        volumePercent = settings.volumePercent.first()
        crescendo = settings.crescendo.first()
        defaultSound = settings.defaultSound.first()
        soundPolicy = settings.soundPolicy.first()
        showImages = settings.showImages.first()
        themeMode = settings.themeMode.first()
        autoUpdateCheck = settings.autoUpdateCheck.first()
        try {
            myName = api.getMe().displayName
        } catch (e: Exception) {
            accountError = context.getString(R.string.settings_account_load_failed, e.toUserMessage(context))
        }
    }

    // Permissions are granted/revoked in the system Settings app, so
    // re-check every time we come back from there.
    OnResume {
        dndAccessGranted = context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        readiness = DeviceReadiness.check(context)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            SectionCard(stringResource(R.string.settings_account)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Avatar(myName ?: "?", size = 48.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(myName ?: stringResource(R.string.common_loading), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.settings_name_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(enabled = myName != null, onClick = { renaming = true }) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.settings_rename))
                    }
                }
                accountError?.let {
                    StatusPill(StatusKind.ERROR, it, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    icon = Icons.Rounded.Key,
                    title = stringResource(R.string.settings_rotate_key),
                    supporting = stringResource(if (keyRotated) R.string.settings_rotate_key_done else R.string.settings_rotate_key_hint),
                    onClick = { confirmRotateKey = true },
                )
                SettingsRow(
                    icon = Icons.Rounded.DeleteForever,
                    title = stringResource(R.string.settings_delete_account),
                    supporting = stringResource(R.string.settings_delete_account_hint),
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmDelete = true },
                )
            }

            SectionCard(stringResource(R.string.settings_receiving)) {
                SettingsRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    title = stringResource(R.string.settings_armed),
                    supporting = stringResource(if (armed) R.string.settings_armed_on else R.string.settings_armed_off),
                    trailing = {
                        Switch(checked = armed, onCheckedChange = { value ->
                            armed = value
                            scope.launch { settings.setArmed(value) }
                        })
                    },
                )
                SettingsRow(
                    icon = Icons.Rounded.DoNotDisturbOn,
                    title = stringResource(R.string.settings_dnd_bypass),
                    supporting = if (dndBypass && !dndAccessGranted) {
                        stringResource(R.string.settings_dnd_needs_access)
                    } else {
                        stringResource(R.string.settings_dnd_hint)
                    },
                    trailing = {
                        Switch(checked = dndBypass, onCheckedChange = { value ->
                            if (value && !dndAccessGranted) {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                            }
                            dndBypass = value
                            scope.launch { settings.setDndBypassEnabled(value) }
                        })
                    },
                )
                SettingsRow(
                    icon = Icons.Rounded.TouchApp,
                    title = stringResource(R.string.settings_tap_dismiss),
                    supporting = stringResource(if (dismissMode == DismissMode.TAP_TO_DISMISS) R.string.settings_tap_dismiss_on else R.string.settings_tap_dismiss_off),
                    trailing = {
                        Switch(checked = dismissMode == DismissMode.TAP_TO_DISMISS, onCheckedChange = { tapToDismiss ->
                            val mode = if (tapToDismiss) DismissMode.TAP_TO_DISMISS else DismissMode.UNLOCK_REQUIRED
                            dismissMode = mode
                            scope.launch { settings.setDismissMode(mode) }
                        })
                    },
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    title = stringResource(R.string.settings_volume, volumePercent),
                    supporting = stringResource(R.string.settings_volume_hint),
                )
                Slider(
                    value = volumePercent.toFloat(),
                    onValueChange = { volumePercent = it.roundToInt() },
                    onValueChangeFinished = {
                        scope.launch { settings.setVolumePercent(volumePercent) }
                        SoundPreview.play(context, parseSoundRef(defaultSound) ?: SoundRef.Device, volumePercent)
                    },
                    valueRange = SettingsRepository.MIN_VOLUME_PERCENT.toFloat()..100f,
                    steps = (100 - SettingsRepository.MIN_VOLUME_PERCENT) / 10 - 1,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.TrendingUp,
                    title = stringResource(R.string.settings_crescendo),
                    supporting = stringResource(R.string.settings_crescendo_hint),
                    trailing = {
                        Switch(checked = crescendo, onCheckedChange = { value ->
                            crescendo = value
                            scope.launch { settings.setCrescendo(value) }
                        })
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(
                    icon = Icons.Rounded.MusicNote,
                    title = stringResource(R.string.settings_default_sound),
                    supporting = soundLabel(defaultSound),
                    onClick = { pickingDefaultSound = true },
                )
                SettingsRow(
                    icon = Icons.Rounded.RecordVoiceOver,
                    title = stringResource(R.string.settings_sound_policy),
                    supporting = stringResource(soundPolicyLabel(soundPolicy)),
                    onClick = { pickingPolicy = true },
                )
                SettingsRow(
                    icon = Icons.Rounded.Image,
                    title = stringResource(R.string.settings_show_images),
                    supporting = stringResource(R.string.settings_show_images_hint),
                    trailing = {
                        Switch(checked = showImages, onCheckedChange = { value ->
                            showImages = value
                            scope.launch { settings.setShowImages(value) }
                        })
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    onClick = {
                        val intent = Intent(context, AlarmForegroundService::class.java).apply {
                            putExtra(AlarmForegroundService.EXTRA_TRIGGER_ID, -1L)
                            putExtra(AlarmForegroundService.EXTRA_FROM_DISPLAY_NAME, context.getString(R.string.settings_test_sender))
                        }
                        ContextCompat.startForegroundService(context, intent)
                    },
                ) {
                    Text(stringResource(R.string.settings_test_alarm))
                }
            }

            readiness?.let { r ->
                SectionCard(stringResource(R.string.settings_readiness)) {
                    ReadinessRow(
                        Icons.Rounded.NotificationsActive, stringResource(R.string.settings_ready_notifications), stringResource(R.string.settings_ready_notifications_hint), r.notificationsEnabled,
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                    ReadinessRow(
                        Icons.Rounded.PhoneLocked, stringResource(R.string.settings_ready_full_screen), stringResource(R.string.settings_ready_full_screen_hint), r.fullScreenIntentAllowed,
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                    ReadinessRow(
                        Icons.Rounded.BatteryAlert, stringResource(R.string.settings_ready_battery), stringResource(R.string.settings_ready_battery_hint), r.ignoringBatteryOptimizations,
                    ) {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        )
                    }
                }
            }

            SectionCard(stringResource(R.string.settings_display)) {
                SettingsRow(
                    icon = Icons.Rounded.DarkMode,
                    title = stringResource(R.string.settings_theme),
                    supporting = stringResource(themeLabel(themeMode)),
                    onClick = { pickingTheme = true },
                )
            }

            if (AppUpdater.isEnabled) {
                SectionCard(stringResource(R.string.settings_updates)) {
                    SettingsRow(
                        icon = Icons.Rounded.SystemUpdate,
                        title = stringResource(R.string.settings_check_update),
                        supporting = when {
                            checkingUpdate -> stringResource(R.string.settings_checking_update)
                            updateStatus != null -> updateStatus
                            else -> stringResource(R.string.settings_current_version, BuildConfig.VERSION_NAME)
                        },
                        onClick = if (checkingUpdate) null else {
                            {
                                checkingUpdate = true
                                updateStatus = null
                                scope.launch {
                                    try {
                                        val found = AppUpdater.checkForUpdate()
                                        if (found == null) {
                                            updateStatus = context.getString(R.string.settings_up_to_date, BuildConfig.VERSION_NAME)
                                        } else {
                                            availableUpdate = found
                                            updateStatus = context.getString(R.string.settings_update_available, found.versionName)
                                        }
                                    } catch (e: Exception) {
                                        updateStatus = context.getString(R.string.settings_update_check_failed, e.message ?: context.getString(R.string.settings_network_error))
                                    } finally {
                                        checkingUpdate = false
                                    }
                                }
                            }
                        },
                    )
                    SettingsRow(
                        icon = Icons.Rounded.Update,
                        title = stringResource(R.string.settings_auto_update),
                        supporting = stringResource(R.string.settings_auto_update_hint),
                        trailing = {
                            Switch(checked = autoUpdateCheck, onCheckedChange = { value ->
                                autoUpdateCheck = value
                                scope.launch { settings.setAutoUpdateCheck(value) }
                                if (value) UpdateCheckWorker.schedule(context) else UpdateCheckWorker.cancel(context)
                            })
                        },
                    )
                }
            }
        }
    }

    if (renaming) {
        var draft by remember { mutableStateOf(myName ?: "") }
        var renameError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            icon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_rename_title)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_DISPLAY_NAME_LENGTH) },
                    singleLine = true,
                    isError = renameError != null,
                    supportingText = { Text(renameError ?: "${draft.length}/$MAX_DISPLAY_NAME_LENGTH") },
                )
            },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            myName = api.patchMe(PatchMeRequest(displayName = draft.trim())).displayName
                            renaming = false
                        } catch (e: Exception) {
                            renameError = e.toUserMessage(context)
                        }
                    }
                }) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (pickingDefaultSound) {
        SoundPickerSheet(
            title = stringResource(R.string.settings_default_sound),
            selected = defaultSound,
            onSelect = { key ->
                val chosen = key ?: SettingsRepository.DEFAULT_SOUND_KEY
                defaultSound = chosen
                scope.launch { settings.setDefaultSound(chosen) }
            },
            onDismiss = { pickingDefaultSound = false },
        )
    }

    if (pickingTheme) {
        ChoiceDialog(
            icon = Icons.Rounded.DarkMode,
            title = stringResource(R.string.settings_theme),
            options = ThemeMode.entries.map { it to stringResource(themeLabel(it)) },
            selected = themeMode,
            onSelect = { mode ->
                themeMode = mode
                scope.launch { settings.setThemeMode(mode) }
                pickingTheme = false
            },
            onDismiss = { pickingTheme = false },
        )
    }

    if (pickingPolicy) {
        ChoiceDialog(
            icon = Icons.Rounded.RecordVoiceOver,
            title = stringResource(R.string.settings_sound_policy),
            options = SoundPolicy.entries.map { it to stringResource(soundPolicyLabel(it)) },
            selected = soundPolicy,
            onSelect = { policy ->
                soundPolicy = policy
                scope.launch { settings.setSoundPolicy(policy) }
                pickingPolicy = false
            },
            onDismiss = { pickingPolicy = false },
        )
    }

    availableUpdate?.let { info ->
        UpdateDialog(info = info, onDismiss = { availableUpdate = null })
    }

    if (confirmRotateKey) {
        AlertDialog(
            onDismissRequest = { confirmRotateKey = false },
            icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_rotate_title)) },
            text = { Text(stringResource(R.string.settings_rotate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRotateKey = false
                    scope.launch {
                        try {
                            val rotated = api.rotateApiKey()
                            tokenStore.save(tokenStore.userId, rotated.apiKey)
                            keyRotated = true
                            accountError = null
                        } catch (e: Exception) {
                            accountError = context.getString(R.string.settings_rotate_failed, e.toUserMessage(context))
                        }
                    }
                }) { Text(stringResource(R.string.settings_rotate_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmRotateKey = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.settings_delete_title)) },
            text = { Text(stringResource(R.string.settings_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            api.deleteMe().orThrow()
                            tokenStore.clear()
                            onAccountDeleted()
                        } catch (e: Exception) {
                            accountError = context.getString(R.string.settings_delete_failed, e.toUserMessage(context))
                        }
                    }
                }) { Text(stringResource(R.string.settings_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ReadinessRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    ok: Boolean,
    onFix: () -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        supporting = supporting,
        iconTint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        onClick = if (ok) null else onFix,
        trailing = {
            if (ok) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.settings_ready_done), tint = WakeTheme.status.success)
            } else {
                TextButton(onClick = onFix) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text(stringResource(R.string.settings_ready_fix))
                }
            }
        },
    )
}

private fun soundPolicyLabel(policy: SoundPolicy): Int = when (policy) {
    SoundPolicy.ALLOW_ALL -> R.string.settings_sound_policy_all
    SoundPolicy.PRESETS_ONLY -> R.string.settings_sound_policy_presets
    SoundPolicy.MINE_ONLY -> R.string.settings_sound_policy_mine
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/** A single-choice dialog: tapping an option picks it and closes. */
@Composable
private fun <T> ChoiceDialog(
    icon: ImageVector,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = selected == value, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
