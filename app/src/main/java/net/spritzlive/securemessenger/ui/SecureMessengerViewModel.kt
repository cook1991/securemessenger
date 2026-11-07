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
import net.spritzlive.securemessenger.data.Message
import net.spritzlive.securemessenger.net.NetworkRepository
import net.spritzlive.securemessenger.net.RelayConnectionService

/**
 * Binds to RelayConnectionService rather than owning a NetworkRepository directly —
 * the service owns the one live connection for the app's lifetime (including while
 * backgrounded); this just observes it while a UI is actually looking. See the
 * service's own doc for why a single shared instance matters here.
 */
class SecureMessengerViewModel(application: Application) : AndroidViewModel(application) {
    private var repo: NetworkRepository? = null
    private var bound = false

    val peerDisplayName: StateFlow<String> get() = _peerDisplayName
    private val _peerDisplayName = MutableStateFlow("")

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("connecting")
    val status: StateFlow<String> = _status.asStateFlow()

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as RelayConnectionService.LocalBinder).getService()
            val r = service.repository
            repo = r
            _peerDisplayName.value = r.displayName
            r.onStateChange = { line -> _status.value = line }
            r.onMessagesChanged = { refresh() }
            _status.value = r.currentState
            refresh()
            // ThreadScreen already calls markRead() when isThreadVisible flips true,
            // but bindService's callback is asynchronous — if that flip happened
            // before this fired, markRead() would have no-op'd against a still-null
            // repo. Covers that race for whatever arrived before binding completed.
            markRead()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            repo = null
        }
    }

    fun bind() {
        if (bound) return
        val app = getApplication<Application>()
        val intent = Intent(app, RelayConnectionService::class.java)
        ContextCompat.startForegroundService(app, intent)
        app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    fun unbind() {
        if (!bound) return
        getApplication<Application>().unbindService(connection)
        bound = false
        repo = null
    }

    fun send(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { repo?.sendMessage(trimmed) }
    }

    fun shred(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repo?.shred(id) }
    }

    fun markRead() {
        viewModelScope.launch(Dispatchers.IO) { repo?.markThreadRead() }
    }

    private fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = repo?.loadThread() ?: emptyList()
        }
    }

    override fun onCleared() {
        unbind()
    }
}
