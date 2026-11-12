package net.spritzlive.securemessenger.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.data.Message
import net.spritzlive.securemessenger.net.NetworkRepository
import net.spritzlive.securemessenger.net.RelayConnectionService

/** Binds the relay service while a screen is composed — see [ThreadViewModel] and
 * [ChatListViewModel]. The service owns the one live connection; this only observes
 * it while a UI is actually looking. */
abstract class RelayBoundViewModel(application: Application) : AndroidViewModel(application) {
    protected var repo: NetworkRepository? = null
        private set
    private var bound = false

    private val stateListener: (String) -> Unit = { line -> onState(line) }
    private val messagesListener: () -> Unit = { refresh() }

    protected open fun onState(line: String) {}
    protected abstract fun refresh()
    protected open fun onBound(r: NetworkRepository) {}

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val r = (binder as RelayConnectionService.LocalBinder).getService().repository
            repo = r
            r.addStateListener(stateListener)
            r.addMessagesListener(messagesListener)
            onState(r.currentState)
            onBound(r)
            refresh()
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
        repo?.removeStateListener(stateListener)
        repo?.removeMessagesListener(messagesListener)
        getApplication<Application>().unbindService(connection)
        bound = false
        repo = null
    }

    override fun onCleared() {
        unbind()
    }
}

/** One contact's conversation; [peerId] comes from the navigation route. */
class ThreadViewModel(application: Application, savedStateHandle: SavedStateHandle) : RelayBoundViewModel(application) {
    val peerId: String = checkNotNull(savedStateHandle["peerId"])

    private val _peerDisplayName = MutableStateFlow("")
    val peerDisplayName: StateFlow<String> = _peerDisplayName.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _status = MutableStateFlow("connecting")
    val status: StateFlow<String> = _status.asStateFlow()

    override fun onState(line: String) {
        _status.value = line
    }

    override fun onBound(r: NetworkRepository) {
        // ThreadScreen already calls markRead() when it becomes visible, but
        // bindService's callback is asynchronous — if that happened before this
        // fired, markRead() no-op'd against a still-null repo. Covers that race.
        markRead()
    }

    fun send(body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { repo?.sendMessage(peerId, trimmed) }
    }

    fun shred(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { repo?.shred(id) }
    }

    /** Decode/resize/compress ([ImagePipeline], CPU-bound) then hand the result to
     * NetworkRepository.sendImage (blocking disk/network work).
     *
     * [deleteSourceAfter]: camera capture writes to a throwaway cache file the
     * caller doesn't otherwise clean up — true deletes it once this coroutine is done
     * reading it, never before. False for a picker Uri, which the system — not this
     * app — owns and must not delete. */
    fun sendImage(context: Context, uri: Uri, deleteSourceAfter: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            val jpegBytes = runCatching { ImagePipeline.loadResizedJpeg(context, uri) }.getOrNull()
            if (jpegBytes != null) {
                kotlinx.coroutines.withContext(Dispatchers.IO) { repo?.sendImage(peerId, jpegBytes) }
            }
            if (deleteSourceAfter) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    fun markRead() {
        viewModelScope.launch(Dispatchers.IO) { repo?.markThreadRead(peerId) }
    }

    override fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val r = repo ?: return@launch
            _peerDisplayName.value = r.displayName(peerId) ?: ""
            _messages.value = r.loadThread(peerId)
        }
    }
}
