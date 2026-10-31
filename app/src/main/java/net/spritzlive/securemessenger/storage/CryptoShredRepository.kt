package net.spritzlive.securemessenger.storage

import android.content.Context
import net.spritzlive.securemessenger.crypto.wipe
import net.spritzlive.securemessenger.data.Message
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

    fun store(peerId: String, body: String, date: Long, outgoing: Boolean, status: MessageStatus): Long {
        val (keyId, keyBytes) = keyStore.generateAndStoreKey()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"))
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(body.toByteArray(Charsets.UTF_8))
            return db.insertMessage(peerId, date, outgoing, status, keyId, iv, ciphertext)
        } finally {
            keyBytes.wipe()
        }
    }

    fun updateStatus(id: Long, status: MessageStatus) {
        db.updateStatus(id, status)
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
            val body = String(plaintext, Charsets.UTF_8)
            plaintext.wipe()
            return Message(id, peerId, body, date, outgoing, status, keyId, shredded = false)
        } finally {
            keyBytes.wipe()
        }
    }

    private fun RawMessageRow.tombstone(): Message =
        Message(id, peerId, "", date, outgoing, status, keyId, shredded = true)
}
