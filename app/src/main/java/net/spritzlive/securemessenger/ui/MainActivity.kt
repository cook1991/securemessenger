package net.spritzlive.securemessenger.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import net.spritzlive.securemessenger.net.hermes.HermesConnectionService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.crypto.LibsignalSpike
import net.spritzlive.securemessenger.pairing.ContactVerificationStore
import net.spritzlive.securemessenger.crypto.HermesAuthStore
import net.spritzlive.securemessenger.ui.screens.ChatListScreen
import net.spritzlive.securemessenger.ui.screens.HermesPairingScreen
import net.spritzlive.securemessenger.ui.screens.HermesScreen
import net.spritzlive.securemessenger.ui.screens.PairingScreen
import net.spritzlive.securemessenger.ui.screens.TextSizeScreen
import net.spritzlive.securemessenger.ui.screens.ThreadScreen
import net.spritzlive.securemessenger.ui.theme.SENIOR_FONT_SCALE
import net.spritzlive.securemessenger.ui.theme.SecureMessengerTheme

private const val TAG = "LibsignalSpike"

private const val ROUTE_CHATS = "chats"
private const val ROUTE_THREAD = "thread/{peerId}"
private const val ROUTE_CONTACT = "contact/{peerId}"
private const val ROUTE_ADD_CONTACT = "add_contact"
private const val ROUTE_TEXTSIZE = "textsize"
private const val ROUTE_HERMES = "hermes"
private const val ROUTE_HERMES_PAIRING = "hermes_pairing"

private fun threadRoute(peerId: String) = "thread/$peerId"
private fun contactRoute(peerId: String) = "contact/$peerId"

/** Where a notification tap asked to go. */
private sealed interface OpenRequest {
    data class Contact(val peerId: String) : OpenRequest
    data object Hermes : OpenRequest
}

/**
 * The chat list is always the bottom of the back stack. On launch the app goes one
 * step further on its own when there's an obvious place to be: nothing paired yet →
 * add a contact; exactly one conversation → straight into it (back still reaches
 * the list). A notification tap opens that conversation on top of the list.
 */
class MainActivity : ComponentActivity() {
    // Set by a notification tap, on cold start (onCreate) or with the Activity
    // already running (onNewIntent, launchMode=singleTop).
    private val openRequest = mutableStateOf<OpenRequest?>(null)

    private fun consumeOpenRequest(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(HermesConnectionService.EXTRA_OPEN_HERMES, false)) {
            intent.removeExtra(HermesConnectionService.EXTRA_OPEN_HERMES)
            openRequest.value = OpenRequest.Hermes
        }
        intent.getStringExtra(EXTRA_PEER_ID)?.let { peerId ->
            intent.removeExtra(EXTRA_PEER_ID)
            openRequest.value = OpenRequest.Contact(peerId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeOpenRequest(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeOpenRequest(intent)
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

                // After a new pairing or re-pair: that conversation, on top of the list
                // (a re-pair can change the peerId, so the old thread entry goes too).
                fun openThreadFromList(peerId: String) {
                    navController.navigate(threadRoute(peerId)) { popUpTo(ROUTE_CHATS) }
                }

                NavHost(navController = navController, startDestination = ROUTE_CHATS) {
                    composable(ROUTE_CHATS) {
                        ChatListScreen(
                            vm = viewModel(),
                            onOpenContact = { navController.navigate(threadRoute(it)) },
                            onOpenHermes = { navController.navigate(ROUTE_HERMES) },
                            onAddContact = { navController.navigate(ROUTE_ADD_CONTACT) },
                            onSetUpHermes = { navController.navigate(ROUTE_HERMES) },
                            onTextSize = { navController.navigate(ROUTE_TEXTSIZE) },
                        )
                    }
                    composable(ROUTE_ADD_CONTACT) {
                        PairingScreen(
                            onBack = { navController.popBackStack() },
                            onPaired = { openThreadFromList(it) },
                        )
                    }
                    composable(ROUTE_CONTACT) { entry ->
                        PairingScreen(
                            existingPeerId = entry.arguments?.getString("peerId"),
                            onBack = { navController.popBackStack() },
                            onPaired = { openThreadFromList(it) },
                        )
                    }
                    composable(ROUTE_THREAD) { entry ->
                        val peerId = entry.arguments?.getString("peerId").orEmpty()
                        ThreadScreen(
                            onBack = { navController.popBackStack() },
                            onNavigateToPairing = { navController.navigate(contactRoute(peerId)) },
                            onNavigateToTextSize = { navController.navigate(ROUTE_TEXTSIZE) },
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

                // Once per launch (not on rotation/recreation, which restores the back
                // stack itself), unless a notification tap already says where to go.
                var autoOpened by rememberSaveable { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (autoOpened) return@LaunchedEffect
                    autoOpened = true
                    if (openRequest.value != null) return@LaunchedEffect
                    val contacts = ContactVerificationStore(applicationContext).loadAll()
                    val hermesPaired = HermesAuthStore(applicationContext).isPaired()
                    when {
                        contacts.isEmpty() && !hermesPaired -> navController.navigate(ROUTE_ADD_CONTACT)
                        contacts.size == 1 && !hermesPaired -> navController.navigate(threadRoute(contacts[0].peerId))
                        contacts.isEmpty() && hermesPaired -> navController.navigate(ROUTE_HERMES)
                    }
                }

                val request by openRequest
                androidx.compose.runtime.LaunchedEffect(request) {
                    val r = request ?: return@LaunchedEffect
                    when (r) {
                        is OpenRequest.Contact -> navController.navigate(threadRoute(r.peerId)) { popUpTo(ROUTE_CHATS) }
                        OpenRequest.Hermes -> navController.navigate(ROUTE_HERMES) { popUpTo(ROUTE_CHATS) }
                    }
                    openRequest.value = null
                }
            }
        }
    }
}
