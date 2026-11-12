package net.spritzlive.securemessenger.net.hermes

import android.content.Context
import net.spritzlive.securemessenger.crypto.HermesAuthStore
import net.spritzlive.securemessenger.crypto.wipe
import net.spritzlive.securemessenger.storage.HermesMessage
import net.spritzlive.securemessenger.storage.HermesMessageStatus
import net.spritzlive.securemessenger.storage.HermesMessageStore

/**
 * Ties HermesClient + HermesAuthStore + HermesMessageStore + the ntfy wake signal
 * together — the Hermes-side equivalent of NetworkRepository, kept as a fully
 * separate object so a bug here can't reach the primary contact's
 * NetworkRepository/RelayClient at all (no shared instances, no shared state
 * beyond the Android process itself).
 */
class HermesRepository(context: Context) {
    private val auth = HermesAuthStore(context)
    private val messages = HermesMessageStore(context)
    private val client = HermesClient(auth)
    private val ntfy = NtfyWakeClient()

    var onStateChange: ((String) -> Unit)? = null
    var currentState: String = "disconnected"
        private set

    var onMessagesChanged: (() -> Unit)? = null
    var onIncomingMessage: (() -> Unit)? = null
    var onNeedsPairing: (() -> Unit)? = null

    val isPaired: Boolean get() = auth.loadDeviceToken() != null

    init {
        client.onStateChange = { currentState = it; onStateChange?.invoke(it) }
        client.onNeedsPairing = { onNeedsPairing?.invoke() }
        client.onAuthRejected = { onNeedsPairing?.invoke() }
        client.onMessage = { id, ts, text ->
            messages.insert(id, text, outgoing = false, ts = ts, status = HermesMessageStatus.RECEIVED)
            onMessagesChanged?.invoke()
            onIncomingMessage?.invoke()
        }
        client.onAck = { id -> messages.updateStatusByFrameId(id, HermesMessageStatus.ACKED); onMessagesChanged?.invoke() }

        ntfy.onStateChange = { line -> onStateChange?.invoke("ntfy: $line") }
        // Purely a "go check" signal — reconnect (which also triggers a `resume`)
        // is the only reaction; no content ever travels through ntfy itself.
        ntfy.onWake = { client.connect() }
    }

    fun connect() {
        client.connect()
        ntfy.start()
    }

    fun disconnect() {
        client.close()
        ntfy.stop()
    }

    fun completePairing(deviceToken: ByteArray) {
        auth.saveDeviceToken(deviceToken)
        deviceToken.wipe()
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val id = client.sendMessage(trimmed)
        if (id != null) {
            messages.insert(id, trimmed, outgoing = true, ts = System.currentTimeMillis(), status = HermesMessageStatus.SENT)
        }
        onMessagesChanged?.invoke()
    }

    fun loadMessages(): List<HermesMessage> = messages.loadAll()
}
