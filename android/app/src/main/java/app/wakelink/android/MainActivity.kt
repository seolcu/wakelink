package app.wakelink.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.wakelink.android.contacts.ContactsScreen
import app.wakelink.android.data.TokenStore
import app.wakelink.android.pairing.PairingScreen
import app.wakelink.android.pairing.RegisterScreen
import app.wakelink.android.settings.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startDestination = if (TokenStore(applicationContext).isRegistered) "contacts" else "register"

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("register") {
                            RegisterScreen(onRegistered = {
                                navController.navigate("contacts") {
                                    popUpTo("register") { inclusive = true }
                                }
                            })
                        }
                        composable("contacts") {
                            ContactsScreen(
                                onOpenPairing = { navController.navigate("pairing") },
                                onOpenSettings = { navController.navigate("settings") },
                            )
                        }
                        composable("pairing") {
                            PairingScreen(onDone = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(onDone = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
