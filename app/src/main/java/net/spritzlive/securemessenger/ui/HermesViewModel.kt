package net.spritzlive.securemessenger.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.net.hermes.HermesConnectionService
import net.spritzlive.securemessenger.net.hermes.HermesPairingClient
import net.spritzlive.securemessenger.net.hermes.HermesRepository
import net.spritzlive.securemessenger.storage.HermesMessage

/** Same bind-to-a-foreground-service shape as SecureMessengerViewModel, pointed at
 * HermesConnectionService instead — kept as its own class rather than a shared base,
 * since the two have nothing in common beyond that shape and sharing one would
 * couple the Hermes feature's churn to the primary messaging ViewModel. */
class HermesViewModel(application: Application) : AndroidViewModel(application) {
    private var repo: HermesRepository? = null
    private var bound = false

    private val _messages = MutableStateFlow<List<HermesMessage>>(emptyList())
    val messages: StateFlow<List<HermesMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("disconnected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _needsPairing = MutableStateFlow(false)
    val needsPairing: StateFlow<Boolean> = _needsPairing.asStateFlow()

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as HermesConnectionService.LocalBinder).getService()
            val r = service.repository
            repo = r
            _needsPairing.value = !r.isPaired
            r.onStateChange = { line -> _status.value = line }
            r.onMessagesChanged = { refresh() }
            r.onNeedsPairing = { _needsPairing.value = true }
            _status.value = r.currentState
            refresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            repo = null
        }
    }

    // Bound for this ViewModel's whole lifetime, not per-screen: it's shared by
    // HermesScreen and HermesPairingScreen (Activity-scoped, see MainActivity), and
    // tying the binding to either screen's composition would drop `repo` mid-pairing
    // when navigating between them — losing the freshly-issued token.
    init {
        val app = getApplication<Application>()
        val intent = Intent(app, HermesConnectionService::class.java)
        ContextCompat.startForegroundService(app, intent)
        app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    private fun unbind() {
        if (!bound) return
        getApplication<Application>().unbindService(connection)
        bound = false
        repo = null
    }

    fun send(text: String) {
        viewModelScope.launch(Dispatchers.IO) { repo?.send(text) }
    }

    /** Exchanges the pairing code for a device token over HTTPS, stores it, then
     * starts the Hermes connection — a failure (bad code, network error) surfaces
     * via [pairingError] rather than throwing, since this runs off a UI click. */
    fun submitPairingCode(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _pairingError.value = null
            // Check before spending the code: it's single-use, and redeeming it
            // with nowhere to store the token would burn it for nothing.
            val r = repo ?: run {
                _pairingError.value = "Not connected to the Hermes service yet — try again in a moment"
                return@launch
            }
            HermesPairingClient().exchangeCodeForToken(code)
                .onSuccess { token ->
                    r.completePairing(token)
                    _needsPairing.value = false
                    r.connect()
                }
                .onFailure { e -> _pairingError.value = e.message ?: "pairing failed" }
        }
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = repo?.loadMessages() ?: emptyList()
        }
    }

    override fun onCleared() {
        unbind()
    }
}
