package net.spritzlive.securemessenger.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.spritzlive.securemessenger.crypto.HermesAuthStore
import net.spritzlive.securemessenger.net.hermes.HermesConnectionService
import net.spritzlive.securemessenger.pairing.ContactVerificationStore
import net.spritzlive.securemessenger.storage.HermesMessageStore

/** One row of the chat list. [peerId] is null for the Hermes row. Never carries
 * message content — the list shows who and when, not what. */
data class ChatRow(val peerId: String?, val name: String, val lastMessageAt: Long?, val hasUnread: Boolean)

class ChatListViewModel(application: Application) : RelayBoundViewModel(application) {
    private val app = application
    private val contactStore = ContactVerificationStore(app)
    private val hermesAuth = HermesAuthStore(app)

    private val _rows = MutableStateFlow<List<ChatRow>>(emptyList())
    val rows: StateFlow<List<ChatRow>> = _rows.asStateFlow()

    private val _hermesPaired = MutableStateFlow(hermesAuth.isPaired())
    val hermesPaired: StateFlow<Boolean> = _hermesPaired.asStateFlow()

    init {
        viewModelScope.launch { HermesConnectionService.incomingVersion.collect { refresh() } }
    }

    /** Binds the relay service (only when there's a contact to talk to) and starts
     * the Hermes service when Hermes is set up, so both keep receiving after the
     * list is opened, not only after a conversation is. */
    fun start() {
        if (contactStore.loadAll().isNotEmpty()) bind()
        if (hermesAuth.isPaired()) {
            ContextCompat.startForegroundService(app, Intent(app, HermesConnectionService::class.java))
        }
        refresh()
    }

    fun stop() = unbind()

    public override fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val contactRows = repo?.chatSummaries()?.map { ChatRow(it.peerId, it.displayName, it.lastMessageAt, it.hasUnread) }
                // Not bound yet (or no contacts): names only until the service is up.
                ?: contactStore.loadAll().map { ChatRow(it.peerId, it.displayName, null, false) }
            val paired = hermesAuth.isPaired()
            _hermesPaired.value = paired
            val hermesRow = if (paired) hermesRow() else null
            _rows.value = (contactRows + listOfNotNull(hermesRow))
                .sortedWith(compareByDescending<ChatRow> { it.lastMessageAt ?: Long.MIN_VALUE }.thenBy { it.name })
        }
    }

    private fun hermesRow(): ChatRow {
        val (last, lastIncoming) = runCatching {
            HermesMessageStore(app).use { it.lastTimestamp() to it.lastTimestamp(incomingOnly = true) }
        }.getOrDefault(null to null)
        val unread = lastIncoming != null && lastIncoming > HermesConnectionService.lastSeen(app)
        return ChatRow(null, "Hermes", last, unread)
    }
}
