package app.wakelink.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.wakelink.android.contacts.ContactsScreen
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.ThemeMode
import app.wakelink.android.data.TokenStore
import app.wakelink.android.pairing.PairingScreen
import app.wakelink.android.pairing.RegisterScreen
import app.wakelink.android.pairing.parseInviteCode
import app.wakelink.android.settings.SettingsScreen
import app.wakelink.android.status.DeviceReadiness
import app.wakelink.android.status.SetupScreen
import app.wakelink.android.ui.theme.WakeLinkTheme
import app.wakelink.android.update.AppUpdater
import app.wakelink.android.update.UpdateDialog
import app.wakelink.android.update.UpdateInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * An invite code from a `wakelink://invite/CODE` link, waiting to be
     * shown on the pairing screen. It only pre-fills the code field -- the
     * user still has to tap "connect" themselves, so following a link can
     * never pair anyone without their explicit consent.
     */
    private var pendingInviteCode by mutableStateOf<String?>(null)

    /** Opened from the "new version available" notification: show the update dialog right away. */
    private var updateRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge is forced at targetSdk 35 anyway; this also picks
        // status-bar icon colors that contrast with the light/dark background.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val startDestination = if (TokenStore(applicationContext).isRegistered) "contacts" else "register"
        if (savedInstanceState == null) {
            pendingInviteCode = parseInviteCode(intent?.dataString)
            updateRequested = intent?.getBooleanExtra(EXTRA_SHOW_UPDATE, false) == true
        }

        setContent {
            val themeMode by remember { SettingsRepository(applicationContext).themeMode }.collectAsState(initial = ThemeMode.SYSTEM)
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status/navigation bar icons must contrast with the chosen theme,
            // not just the system's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            WakeLinkTheme(darkTheme = dark) {
                // Background (not surface) color, so the status-bar strip
                // above each screen matches the screen itself.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StartupUpdateCheck()

                    val navController = rememberNavController()
                    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

                    // Once per launch (and right after registering, which lands
                    // here too): if anything a wake depends on is off -- the
                    // notification permission, full-screen alarms, battery
                    // optimization -- walk the user through fixing it.
                    var setupChecked by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(currentRoute) {
                        if (setupChecked || currentRoute != "contacts") return@LaunchedEffect
                        setupChecked = true
                        val readiness = DeviceReadiness.check(this@MainActivity)
                        val dndMissing = SettingsRepository(applicationContext).dndBypassEnabled.first() && !readiness.dndAccessGranted
                        if (!readiness.allReady || dndMissing) navController.navigate("setup")
                    }

                    LaunchedEffect(pendingInviteCode, currentRoute) {
                        val code = pendingInviteCode ?: return@LaunchedEffect
                        // Wait until registered (pairing needs an identity) and set up.
                        if (currentRoute == null || currentRoute == "register" || currentRoute == "setup") return@LaunchedEffect
                        pendingInviteCode = null
                        navController.navigate("pairing?code=$code") {
                            popUpTo("contacts")
                            launchSingleTop = true
                        }
                    }

                    // targetSdk 35 forces edge-to-edge drawing: keep every screen
                    // clear of the status/navigation bars and the keyboard.
                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        composable("register") {
                            RegisterScreen(onRegistered = {
                                navController.navigate("contacts") {
                                    popUpTo("register") { inclusive = true }
                                }
                            })
                        }
                        composable("setup") {
                            SetupScreen(onDone = { navController.popBackStack() })
                        }
                        composable("contacts") {
                            ContactsScreen(
                                onOpenPairing = { navController.navigate("pairing") },
                                onOpenSettings = { navController.navigate("settings") },
                                onOpenSetup = { navController.navigate("setup") },
                                onAccountLost = {
                                    navController.navigate("register") {
                                        popUpTo(navController.graph.id) { inclusive = true }
                                    }
                                },
                            )
                        }
                        composable(
                            "pairing?code={code}",
                            arguments = listOf(navArgument("code") { type = NavType.StringType; nullable = true }),
                        ) { entry ->
                            PairingScreen(
                                initialCode = entry.arguments?.getString("code"),
                                onDone = { navController.popBackStack() },
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                onDone = { navController.popBackStack() },
                                onAccountDeleted = {
                                    navController.navigate("register") {
                                        popUpTo(navController.graph.id) { inclusive = true }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseInviteCode(intent.dataString)?.let { pendingInviteCode = it }
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) updateRequested = true
    }

    /**
     * Once per launch, offer a newer GitHub release if there is one (unless
     * the user turned this off or skipped that exact version). Failures are
     * silent -- being offline at startup isn't worth an error.
     */
    @Composable
    private fun StartupUpdateCheck() {
        var update by rememberSaveable(stateSaver = updateInfoSaver) { mutableStateOf<UpdateInfo?>(null) }
        var checked by rememberSaveable { mutableStateOf(false) }
        val settings = remember { SettingsRepository(applicationContext) }
        LaunchedEffect(Unit) {
            if (checked || updateRequested || !settings.autoUpdateCheck.first()) return@LaunchedEffect
            checked = true
            val found = runCatching { AppUpdater.checkForUpdate() }.getOrNull() ?: return@LaunchedEffect
            if (found.versionName != settings.skippedUpdateVersion.first()) update = found
        }
        // From the notification: the user asked for it, so ignore "skip".
        LaunchedEffect(updateRequested) {
            if (!updateRequested) return@LaunchedEffect
            updateRequested = false
            checked = true
            runCatching { AppUpdater.checkForUpdate() }.getOrNull()?.let { update = it }
        }
        update?.let { info ->
            UpdateDialog(
                info = info,
                onDismiss = { update = null },
                onSkip = {
                    update = null
                    lifecycleScope.launch { settings.setSkippedUpdateVersion(info.versionName) }
                },
            )
        }
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "show_update"
    }
}

/** Keeps a pending update prompt across rotation instead of re-checking. */
private val updateInfoSaver = listSaver<UpdateInfo?, Any>(
    save = { info -> if (info == null) emptyList() else listOf(info.versionName, info.notes, info.apkUrl, info.sizeBytes) },
    restore = { list ->
        if (list.isEmpty()) null
        else UpdateInfo(list[0] as String, list[1] as String, list[2] as String, list[3] as Long)
    },
)
