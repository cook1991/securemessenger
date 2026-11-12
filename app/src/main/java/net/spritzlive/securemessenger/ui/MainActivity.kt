package net.spritzlive.securemessenger.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.crypto.LibsignalSpike
import net.spritzlive.securemessenger.pairing.ContactVerificationStore
import net.spritzlive.securemessenger.ui.screens.HermesPairingScreen
import net.spritzlive.securemessenger.ui.screens.HermesScreen
import net.spritzlive.securemessenger.ui.screens.PairingScreen
import net.spritzlive.securemessenger.ui.screens.TextSizeScreen
import net.spritzlive.securemessenger.ui.screens.ThreadScreen
import net.spritzlive.securemessenger.ui.theme.SENIOR_FONT_SCALE
import net.spritzlive.securemessenger.ui.theme.SecureMessengerTheme

private const val TAG = "LibsignalSpike"

private const val ROUTE_PAIRING = "pairing"
private const val ROUTE_THREAD = "thread"
private const val ROUTE_TEXTSIZE = "textsize"
private const val ROUTE_HERMES = "hermes"
private const val ROUTE_HERMES_PAIRING = "hermes_pairing"

/**
 * Phase 6: real NavHost. Start destination depends on whether a verified contact
 * already exists (ContactVerificationStore) — unpaired starts at PairingScreen,
 * already-paired goes straight to the thread. PairingScreen stays reachable from
 * the thread's top bar afterward too (contact info / re-pair / safety number).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val fontScale by TextSizePreference.flow(applicationContext).collectAsState(initial = SENIOR_FONT_SCALE)

            SecureMessengerTheme(fontScale = fontScale) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val result = LibsignalSpike.runRoundTrip()
                        val text = result.fold(
                            onSuccess = { "PASS: $it" },
                            onFailure = { "FAIL: ${it.javaClass.simpleName}: ${it.message}" },
                        )
                        Log.i(TAG, text)
                    }
                }

                val navController = rememberNavController()
                val startDestination = if (ContactVerificationStore(applicationContext).load() != null) {
                    ROUTE_THREAD
                } else {
                    ROUTE_PAIRING
                }

                NavHost(navController = navController, startDestination = startDestination) {
                    composable(ROUTE_PAIRING) {
                        val canGoBack = navController.previousBackStackEntry != null
                        PairingScreen(onBack = if (canGoBack) ({ navController.popBackStack() }) else null)
                    }
                    composable(ROUTE_THREAD) {
                        ThreadScreen(
                            onNavigateToPairing = { navController.navigate(ROUTE_PAIRING) },
                            onNavigateToTextSize = { navController.navigate(ROUTE_TEXTSIZE) },
                            onNavigateToHermes = { navController.navigate(ROUTE_HERMES) },
                        )
                    }
                    composable(ROUTE_TEXTSIZE) {
                        TextSizeScreen(onBack = { navController.popBackStack() })
                    }
                    // Activity-scoped, not per-route: both Hermes screens must share one
                    // instance (and its service binding) or the pairing screen gets a
                    // fresh, unbound copy that can't store the token it redeems.
                    composable(ROUTE_HERMES) {
                        HermesScreen(
                            onNavigateToPairing = { navController.navigate(ROUTE_HERMES_PAIRING) },
                            onBack = { navController.popBackStack() },
                            vm = viewModel(viewModelStoreOwner = this@MainActivity),
                        )
                    }
                    composable(ROUTE_HERMES_PAIRING) {
                        HermesPairingScreen(
                            onPaired = { navController.popBackStack() },
                            onBack = { navController.popBackStack(ROUTE_HERMES, inclusive = true) },
                            vm = viewModel(viewModelStoreOwner = this@MainActivity),
                        )
                    }
                }
            }
        }
    }
}
