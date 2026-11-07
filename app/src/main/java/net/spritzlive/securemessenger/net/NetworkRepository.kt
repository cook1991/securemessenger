package net.spritzlive.securemessenger.net

import android.content.Context
import net.spritzlive.securemessenger.crypto.RelayAuthKeyStore
import net.spritzlive.securemessenger.crypto.SessionRecordStore
import net.spritzlive.securemessenger.crypto.SignalIdentityStore
import net.spritzlive.securemessenger.data.Message
import net.spritzlive.securemessenger.data.MessageStatus
import net.spritzlive.securemessenger.pairing.ContactVerificationStore
import net.spritzlive.securemessenger.pairing.QrPairing
import net.spritzlive.securemessenger.storage.CryptoShredRepository

/**
 * Ties RelayClient + SessionManager + CryptoShredRepository + the paired contact
 * together — the real transport+crypto+storage pipeline, replacing Phase 3's
 * TempRelayClientSpike. Requires a contact already verified via PairingScreen;
 * construction fails loudly otherwise rather than silently doing nothing.
 *
 * Every method here does blocking disk/crypto/network work — same contract as
 * CryptoShredRepository itself — callers (the ViewModel) are responsible for
 * dispatching off the main thread.
 *
 * Foreground-only for this pass: connect()/disconnect() are meant to be driven by
 * the Activity/ViewModel lifecycle. Background delivery while the app is fully
 * closed needs a foreground service (RelayConnectionService, per the plan) and
 * system notifications on receipt — deliberately deferred to a follow-up once this
 * foreground path is confirmed working end-to-end on real hardware, rather than
 * bundling both into one untested change.
 *
 * Delivery status is optimistic: a sent message is marked SENT once handed to the
 * relay socket, not once the peer's device actually decrypts it. Real DELIVERED
 * tracking needs the relay's delivery-ack message type wired through — not done yet.
 */
class NetworkRepository(context: Context) {
    private val verifiedContact = ContactVerificationStore(context).load()
        ?: error("NetworkRepository requires a paired contact — check PairingScreen state first")

    val peerId: String = verifiedContact.peerId
    val displayName: String = verifiedContact.displayName

    private val repo = CryptoShredRepository(context)
    private val sessionManager: SessionManager
    private val relayClient: RelayClient

    /** Transport/connection state lines (ws open/closed, registered, errors) — for a status indicator, not content. */
    var onStateChange: ((String) -> Unit)? = null

    /** Latest state line, same text an onStateChange subscriber would have seen —
     * lets a late-binding UI (e.g. reopening the app onto an already-connected
     * service) seed itself immediately instead of waiting for the next transition. */
    var currentState: String = "connecting"
        private set

    /** Fires after any local mutation (send, receive, shred) — callers should re-call [loadThread]. */
    var onMessagesChanged: (() -> Unit)? = null

    /** Fires with the plaintext body specifically for a freshly-received message — for notifications. */
    var onIncomingMessage: ((String) -> Unit)? = null

    init {
        val scanned = QrPairing.decode(verifiedContact.scannedPayloadB64)
        val identityStore = SignalIdentityStore(context)
        sessionManager = SessionManager(identityStore, SessionRecordStore(context), peerId, scanned.bundle)

        val auth = RelayAuthKeyStore(context)
        relayClient = RelayClient(
            host = RelayConfig.HOST,
            port = RelayConfig.PORT,
            pinSha256Base64 = RelayConfig.PIN_SHA256_B64,
            trustedCertPem = RelayConfig.CERT_PEM,
            auth = auth,
        )
        relayClient.onStateChange = { currentState = it; onStateChange?.invoke(it) }
        relayClient.onRegistered = { currentState = "connected"; onStateChange?.invoke("connected") }
        relayClient.onBlob = { from, _, ciphertext ->
            runCatching { sessionManager.decrypt(ciphertext) }
                .onSuccess { body ->
                    repo.store(from, body, System.currentTimeMillis(), outgoing = false, status = MessageStatus.RECEIVED)
                    onMessagesChanged?.invoke()
                    onIncomingMessage?.invoke(body)
                }
                .onFailure { e ->
                    onStateChange?.invoke("decrypt failed: ${e.javaClass.simpleName}: ${e.message}")
                }
        }
    }

    fun connect() = relayClient.connect()
    fun disconnect() = relayClient.close()

    fun loadThread(): List<Message> = repo.loadThread(peerId)

    fun sendMessage(body: String) {
        val id = repo.store(peerId, body, System.currentTimeMillis(), outgoing = true, status = MessageStatus.SENDING)
        onMessagesChanged?.invoke()
        val wireBytes = sessionManager.encrypt(body)
        relayClient.sendBlob(peerId, wireBytes)
        repo.updateStatus(id, MessageStatus.SENT)
        onMessagesChanged?.invoke()
    }

    fun shred(messageId: Long) {
        repo.shred(messageId)
        onMessagesChanged?.invoke()
    }
}
