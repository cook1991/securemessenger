package net.spritzlive.securemessenger.storage

import android.content.Context
import net.spritzlive.securemessenger.crypto.wipe
import net.spritzlive.securemessenger.data.Message
import net.spritzlive.securemessenger.data.MessageKind
import net.spritzlive.securemessenger.data.MessageStatus
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val GCM_TAG_BITS = 128

/**
 * Per-message crypto-shredding, end to end: [store] generates a fresh key for each
 * message and encrypts the body under it; [loadThread] unwraps each message's key
 * just long enough to decrypt, then wipes the plaintext key bytes; [shred] destroys
 * a message's key (MessageKeyStore.destroyKey) and clears its now-useless
 * ciphertext, leaving a tombstone row behind.
 */
class CryptoShredRepository(context: Context) {
    private val db = MessageDatabase(context)
    private val keyStore = MessageKeyStore(db)

    fun store(
        peerId: String,
        bodyBytes: ByteArray,
        date: Long,
        outgoing: Boolean,
        status: MessageStatus,
        kind: MessageKind = MessageKind.TEXT,
        receivedBlobId: String? = null,
    ): Long {
        val (keyId, keyBytes) = keyStore.generateAndStoreKey()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(bodyBytes)
            return db.insertMessage(peerId, date, outgoing, status, keyId, iv, ciphertext, kind, receivedBlobId)
        } finally {
            keyBytes.wipe()
        }
    }

    fun updateStatus(id: Long, status: MessageStatus) {
        db.updateStatus(id, status)
    }

    fun updateSentBlobId(id: Long, blobId: String) {
        db.updateSentBlobId(id, blobId)
    }

    fun findIdBySentBlobId(blobId: String): Long? = db.findIdBySentBlobId(blobId)

    fun loadUnackedReceivedBlobIds(peerId: String): List<Pair<Long, String>> =
        db.loadUnackedReceivedBlobIds(peerId)

    fun markReadAckSent(id: Long) {
        db.markReadAckSent(id)
    }

    fun lastMessageDate(peerId: String): Long? = db.lastMessageDate(peerId)

    fun reassignPeer(oldPeerId: String, newPeerId: String) = db.reassignPeer(oldPeerId, newPeerId)

    private val statusRank = mapOf(
        MessageStatus.SENDING to 0,
        MessageStatus.SENT to 1,
        MessageStatus.DELIVERED to 2,
        MessageStatus.READ to 3,
    )

    /** Only ever moves an outgoing message's status forward (SENDING→SENT→DELIVERED→READ)
     *  — acks can arrive out of order or duplicated, and a late 'transport' ack must
     *  never stomp a status that already advanced to READ. */
    fun advanceStatus(id: Long, candidate: MessageStatus) {
        val current = db.loadMessageById(id)?.status ?: return
        val currentRank = statusRank[current] ?: return
        val candidateRank = statusRank[candidate] ?: return
        if (candidateRank > currentRank) db.updateStatus(id, candidate)
    }

    fun loadThread(peerId: String): List<Message> =
        db.loadThreadRaw(peerId).map { row -> row.toMessage() }

    /** Destroys the message's key, then clears the now-undecryptable ciphertext. Irreversible. */
    fun shred(messageId: Long) {
        val row = db.loadMessageById(messageId) ?: return
        keyStore.destroyKey(row.keyId)
        db.markShredded(messageId)
    }

    private fun RawMessageRow.toMessage(): Message {
        if (shredded) return tombstone()

        val keyBytes = keyStore.unwrapKey(keyId) ?: return tombstone()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            return when (kind) {
                MessageKind.TEXT -> {
                    val body = String(plaintext, Charsets.UTF_8)
                    plaintext.wipe()
                    Message(id, peerId, body, date, outgoing, status, keyId, shredded = false, kind = MessageKind.TEXT)
                }
                // Not wiped: the UI needs these bytes to render the image, a
                // deliberate, documented relaxation of the wipe-promptly convention
                // used for text above.
                MessageKind.IMAGE -> Message(
                    id, peerId, "", date, outgoing, status, keyId, shredded = false,
                    kind = MessageKind.IMAGE, imageBytes = plaintext,
                )
            }
        } finally {
            keyBytes.wipe()
        }
    }

    private fun RawMessageRow.tombstone(): Message =
        Message(id, peerId, "", date, outgoing, status, keyId, shredded = true, kind = kind)
}
