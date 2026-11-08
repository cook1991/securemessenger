package net.spritzlive.securemessenger.net

import android.content.Context
import net.spritzlive.securemessenger.crypto.RelayAuthKeyStore
import net.spritzlive.securemessenger.crypto.SessionRecordStore
import net.spritzlive.securemessenger.crypto.SignalIdentityStore
import net.spritzlive.securemessenger.crypto.wipe
import net.spritzlive.securemessenger.data.Message
import net.spritzlive.securemessenger.data.MessageKind
import net.spritzlive.securemessenger.data.MessageStatus
import net.spritzlive.securemessenger.pairing.ContactVerificationStore
import net.spritzlive.securemessenger.pairing.QrPairing
import net.spritzlive.securemessenger.storage.CryptoShredRepository

// Mirrors the relay's raised MAX_CIPHERTEXT_B64 (~900_000 base64 chars) with
// headroom for base64 inflation (~33%) and the JSON envelope around it — a
// pre-send check here means an oversized photo fails immediately and visibly
// instead of being rejected by the relay with no blobId to correlate back to.
private const val MAX_IMAGE_PLAINTEXT_BYTES = 650_000

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
 * Delivery/read status: a sent message starts SENT once handed to the relay
 * socket, then advances to DELIVERED on a "transport" or "app" delivery-ack and to
 * READ once the peer's thread is actually visible (see markThreadRead) — see
 * CryptoShredRepository.advanceStatus for why acks can never move a status
 * backward.
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

    /** Fires on a freshly-received message, for the incoming-message notification —
     * no plaintext passed through: the notification deliberately never shows it. */
    var onIncomingMessage: (() -> Unit)? = null

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
        relayClient.onBlob = { from, blobId, ciphertext ->
            runCatching { sessionManager.decrypt(ciphertext) }
                .onSuccess { envelope ->
                    val (kindByte, payload) = MessageEnvelope.unwrap(envelope)
                    val kind = if (kindByte == MessageEnvelope.KIND_IMAGE) MessageKind.IMAGE else MessageKind.TEXT
                    repo.store(from, payload, System.currentTimeMillis(), outgoing = false,
                        status = MessageStatus.RECEIVED, kind = kind, receivedBlobId = blobId)
                    payload.wipe()
                    relayClient.sendDeliveryAck(from, blobId, "app")
                    onMessagesChanged?.invoke()
                    onIncomingMessage?.invoke()
                }
                .onFailure { e ->
                    onStateChange?.invoke("decrypt failed: ${e.javaClass.simpleName}: ${e.message}")
                }
        }
        relayClient.onDeliveryAck = { _, blobId, level ->
            val id = repo.findIdBySentBlobId(blobId)
            if (id != null) {
                when (level) {
                    "transport", "app" -> repo.advanceStatus(id, MessageStatus.DELIVERED)
                    "read" -> repo.advanceStatus(id, MessageStatus.READ)
                }
                onMessagesChanged?.invoke()
            }
        }
    }

    fun connect() = relayClient.connect()
    fun disconnect() = relayClient.close()

    fun loadThread(): List<Message> = repo.loadThread(peerId)

    private fun sendPayload(kind: Byte, payloadBytes: ByteArray) {
        val messageKind = if (kind == MessageEnvelope.KIND_IMAGE) MessageKind.IMAGE else MessageKind.TEXT
        val id = repo.store(peerId, payloadBytes, System.currentTimeMillis(), outgoing = true,
            status = MessageStatus.SENDING, kind = messageKind)
        onMessagesChanged?.invoke()
        val wireBytes = sessionManager.encrypt(MessageEnvelope.wrap(kind, payloadBytes))
        // blobId is persisted BEFORE the blob goes out, not after: the peer can
        // decrypt and ack faster than this round-trip, and a delivery-ack that
        // arrives before updateSentBlobId had committed would find no matching row
        // (findIdBySentBlobId returns null) and get silently dropped — confirmed live,
        // where the tick only ever advanced once something *later* re-sent an ack
        // (e.g. a "read" ack from reopening the thread), never from the original
        // real-time delivery.
        val blobId = java.util.UUID.randomUUID().toString()
        repo.updateSentBlobId(id, blobId)
        relayClient.sendBlob(peerId, wireBytes, blobId)
        repo.updateStatus(id, MessageStatus.SENT)
        onMessagesChanged?.invoke()
    }

    fun sendMessage(body: String) {
        sendPayload(MessageEnvelope.KIND_TEXT, body.toByteArray(Charsets.UTF_8))
    }

    /** Returns false (storing nothing) if [jpegBytes] is already over the relay's
     * cap — the caller should surface this as a visible error rather than let it
     * disappear into a send that the relay will reject anyway. */
    fun sendImage(jpegBytes: ByteArray): Boolean {
        if (jpegBytes.size > MAX_IMAGE_PLAINTEXT_BYTES) {
            repo.store(peerId, ByteArray(0), System.currentTimeMillis(), outgoing = true,
                status = MessageStatus.FAILED, kind = MessageKind.IMAGE)
            onMessagesChanged?.invoke()
            return false
        }
        sendPayload(MessageEnvelope.KIND_IMAGE, jpegBytes)
        return true
    }

    fun shred(messageId: Long) {
        repo.shred(messageId)
        onMessagesChanged?.invoke()
    }

    /** Sends a deferred 'read' ack for every incoming message this device has
     * decrypted but hasn't yet told the peer a human actually looked at — call this
     * when the thread becomes visible, not at decrypt time (that's the separate
     * 'app' ack in onBlob above). */
    fun markThreadRead() {
        for ((id, blobId) in repo.loadUnackedReceivedBlobIds(peerId)) {
            relayClient.sendDeliveryAck(peerId, blobId, "read")
            repo.markReadAckSent(id)
        }
    }
}
