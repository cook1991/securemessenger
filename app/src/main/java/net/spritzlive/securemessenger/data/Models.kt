package net.spritzlive.securemessenger.data

enum class MessageStatus { SENDING, SENT, DELIVERED, RECEIVED, FAILED }

/**
 * [body] is empty and [shredded] is true once the message's key has been destroyed
 * (see CryptoShredRepository.shred) — the row survives as a tombstone so the thread
 * can show "message deleted" in place rather than leaving a silent gap, but the
 * content itself is permanently unrecoverable at that point.
 */
data class Message(
    val id: Long,
    val peerId: String,
    val body: String,
    val date: Long,
    val outgoing: Boolean,
    val status: MessageStatus,
    val keyId: String,
    val shredded: Boolean,
)
