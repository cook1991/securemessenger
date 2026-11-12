package net.spritzlive.securemessenger.net

import android.content.Context
import android.util.Base64
import net.spritzlive.securemessenger.crypto.RelayAuthKeyStore
import net.spritzlive.securemessenger.crypto.SessionRecordStore
import net.spritzlive.securemessenger.crypto.SignalIdentityStore
import net.spritzlive.securemessenger.crypto.wipe
import net.spritzlive.securemessenger.data.Message
import net.spritzlive.securemessenger.data.MessageKind
import net.spritzlive.securemessenger.data.MessageStatus
import net.spritzlive.securemessenger.pairing.ContactVerificationStore
import net.spritzlive.securemessenger.pairing.QrPairing
import net.spritzlive.securemessenger.pairing.VerifiedContact
import net.spritzlive.securemessenger.storage.CryptoShredRepository
import org.signal.libsignal.protocol.IdentityKey
import java.util.concurrent.CopyOnWriteArraySet

// Mirrors the relay's raised MAX_CIPHERTEXT_B64 (~900_000 base64 chars) with
// headroom for base64 inflation (~33%) and the JSON envelope around it — a
// pre-send check here means an oversized photo fails immediately and visibly
// instead of being rejected by the relay with no blobId to correlate back to.
private const val MAX_IMAGE_PLAINTEXT_BYTES = 650_000

/** What the chat list shows for one contact — never message content. */
data class ChatSummary(val peerId: String, val displayName: String, val lastMessageAt: Long?, val hasUnread: Boolean)

/**
 * Ties RelayClient + one SessionManager per contact + CryptoShredRepository together:
 * the real transport+crypto+storage pipeline. One relay connection serves every
 * contact; each message is routed by its sender's peerId.
 *
 * Contacts are read from ContactVerificationStore on use rather than fixed at
 * construction, so a contact added or re-paired while this is running is picked up
 * without a restart (a re-pair is detected by its changed verifiedAt).
 *
 * Every method here does blocking disk/crypto/network work — callers (the
 * ViewModels) are responsible for dispatching off the main thread.
 *
 * Delivery/read status: a sent message starts SENT once handed to the relay
 * socket, then advances to DELIVERED on a "transport" or "app" delivery-ack and to
 * READ once the peer's thread is actually visible (see markThreadRead) — see
 * CryptoShredRepository.advanceStatus for why acks can never move a status
 * backward.
 */
class NetworkRepository(context: Context) {
    private val appContext = context.applicationContext
    private val contacts = ContactVerificationStore(appContext)
    private val identityStore = SignalIdentityStore(appContext)
    private val repo = CryptoShredRepository(appContext)
    private val relayClient: RelayClient

    // All Signal state (the shared protocol store and every SessionManager) is only
    // touched while holding this lock: incoming blobs arrive on OkHttp's thread while
    // sends come from the UI's IO dispatcher.
    private val cryptoLock = Any()
    private val sessions = HashMap<String, Pair<Long, SessionManager>>()

    private val stateListeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val messageListeners = CopyOnWriteArraySet<() -> Unit>()

    /** Latest transport state line (ws open/closed, registered, errors) — lets a
     * late-binding UI seed itself without waiting for the next transition. */
    var currentState: String = "connecting"
        private set

    /** Fires on a freshly-received message, for the incoming-message notification —
     * no plaintext passed through: the notification deliberately never shows it. */
    var onIncomingMessage: ((peerId: String, displayName: String) -> Unit)? = null

    fun addStateListener(listener: (String) -> Unit) = stateListeners.add(listener)
    fun removeStateListener(listener: (String) -> Unit) = stateListeners.remove(listener)

    /** Fires after any local mutation (send, receive, ack, shred) — re-query after it. */
    fun addMessagesListener(listener: () -> Unit) = messageListeners.add(listener)
    fun removeMessagesListener(listener: () -> Unit) = messageListeners.remove(listener)

    private fun publishState(line: String) {
        currentState = line
        stateListeners.forEach { it(line) }
    }

    private fun messagesChanged() = messageListeners.forEach { it() }

    init {
        val auth = RelayAuthKeyStore(appContext)
        relayClient = RelayClient(
            host = RelayConfig.HOST,
            port = RelayConfig.PORT,
            pinSha256Base64 = RelayConfig.PIN_SHA256_B64,
            trustedCertPem = RelayConfig.CERT_PEM,
            auth = auth,
        )
        relayClient.onStateChange = { publishState(it) }
        relayClient.onRegistered = { publishState("connected") }
        relayClient.onBlob = { from, blobId, ciphertext -> receive(from, blobId, ciphertext) }
        relayClient.onDeliveryAck = { _, blobId, level ->
            val id = repo.findIdBySentBlobId(blobId)
            if (id != null) {
                when (level) {
                    "transport", "app" -> repo.advanceStatus(id, MessageStatus.DELIVERED)
                    "read" -> repo.advanceStatus(id, MessageStatus.READ)
                }
                messagesChanged()
            }
        }
    }

    fun connect() = relayClient.connect()
    fun disconnect() = relayClient.close()

    fun contacts(): List<VerifiedContact> = contacts.loadAll()

    fun displayName(peerId: String): String? = contacts.get(peerId)?.displayName

    fun chatSummaries(): List<ChatSummary> = contacts.loadAll().map { c ->
        ChatSummary(c.peerId, c.displayName, repo.lastMessageDate(c.peerId), repo.loadUnackedReceivedBlobIds(c.peerId).isNotEmpty())
    }

    /** The contact's SessionManager, (re)built if the contact is new or was re-paired;
     * null if [peerId] isn't a verified contact. Caller must hold [cryptoLock]. */
    private fun sessionFor(peerId: String): SessionManager? {
        val contact = contacts.get(peerId) ?: run {
            sessions.remove(peerId)
            return null
        }
        sessions[peerId]?.let { (verifiedAt, manager) -> if (verifiedAt == contact.verifiedAt) return manager }
        val manager = SessionManager(
            identityStore.store,
            SessionRecordStore(appContext, peerId),
            peerId,
            IdentityKey(Base64.decode(contact.identityPublicKeyB64, Base64.NO_WRAP)),
            QrPairing.decode(contact.scannedPayloadB64).bundle,
        )
        sessions[peerId] = contact.verifiedAt to manager
        return manager
    }

    private fun receive(from: String, blobId: String, ciphertext: ByteArray) {
        val result = runCatching {
            synchronized(cryptoLock) {
                // Only verified contacts can reach this device; anything else is dropped.
                val session = sessionFor(from) ?: return
                session.decrypt(ciphertext)
            }
        }
        result
            .onSuccess { envelope ->
                val (kindByte, payload) = MessageEnvelope.unwrap(envelope)
                envelope.wipe()
                val kind = if (kindByte == MessageEnvelope.KIND_IMAGE) MessageKind.IMAGE else MessageKind.TEXT
                repo.store(from, payload, System.currentTimeMillis(), outgoing = false,
                    status = MessageStatus.RECEIVED, kind = kind, receivedBlobId = blobId)
                payload.wipe()
                relayClient.sendDeliveryAck(from, blobId, "app")
                messagesChanged()
                onIncomingMessage?.invoke(from, displayName(from) ?: "")
            }
            .onFailure { e ->
                publishState("decrypt failed: ${e.javaClass.simpleName}")
            }
    }

    fun loadThread(peerId: String): List<Message> = repo.loadThread(peerId)

    private fun sendPayload(peerId: String, kind: Byte, payloadBytes: ByteArray) {
        val messageKind = if (kind == MessageEnvelope.KIND_IMAGE) MessageKind.IMAGE else MessageKind.TEXT
        val id = repo.store(peerId, payloadBytes, System.currentTimeMillis(), outgoing = true,
            status = MessageStatus.SENDING, kind = messageKind)
        messagesChanged()
        val wireBytes = synchronized(cryptoLock) {
            val session = sessionFor(peerId) ?: run {
                payloadBytes.wipe()
                repo.updateStatus(id, MessageStatus.FAILED)
                messagesChanged()
                return
            }
            session.encrypt(MessageEnvelope.wrap(kind, payloadBytes))
        }
        // blobId is persisted BEFORE the blob goes out, not after: the peer can
        // decrypt and ack faster than this round-trip, and a delivery-ack that
        // arrives before updateSentBlobId had committed would find no matching row
        // (findIdBySentBlobId returns null) and get silently dropped — confirmed live.
        val blobId = java.util.UUID.randomUUID().toString()
        repo.updateSentBlobId(id, blobId)
        relayClient.sendBlob(peerId, wireBytes, blobId)
        repo.updateStatus(id, MessageStatus.SENT)
        messagesChanged()
    }

    fun sendMessage(peerId: String, body: String) {
        sendPayload(peerId, MessageEnvelope.KIND_TEXT, body.toByteArray(Charsets.UTF_8))
    }

    /** Returns false (storing nothing) if [jpegBytes] is already over the relay's
     * cap — the caller should surface this as a visible error rather than let it
     * disappear into a send that the relay will reject anyway. */
    fun sendImage(peerId: String, jpegBytes: ByteArray): Boolean {
        if (jpegBytes.size > MAX_IMAGE_PLAINTEXT_BYTES) {
            repo.store(peerId, ByteArray(0), System.currentTimeMillis(), outgoing = true,
                status = MessageStatus.FAILED, kind = MessageKind.IMAGE)
            messagesChanged()
            return false
        }
        sendPayload(peerId, MessageEnvelope.KIND_IMAGE, jpegBytes)
        return true
    }

    fun shred(messageId: Long) {
        repo.shred(messageId)
        messagesChanged()
    }

    /** Sends a deferred 'read' ack for every incoming message from [peerId] this
     * device has decrypted but hasn't yet told the peer a human actually looked at —
     * call this when that thread becomes visible, not at decrypt time (that's the
     * separate 'app' ack in [receive]). */
    fun markThreadRead(peerId: String) {
        val unacked = repo.loadUnackedReceivedBlobIds(peerId)
        for ((id, blobId) in unacked) {
            relayClient.sendDeliveryAck(peerId, blobId, "read")
            repo.markReadAckSent(id)
        }
        if (unacked.isNotEmpty()) messagesChanged()
    }
}
