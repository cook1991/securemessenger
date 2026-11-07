package net.spritzlive.securemessenger.data

enum class MessageStatus { SENDING, SENT, DELIVERED, READ, RECEIVED, FAILED }

enum class MessageKind { TEXT, IMAGE }

/**
 * [body] is empty and [shredded] is true once the message's key has been destroyed
 * (see CryptoShredRepository.shred) — the row survives as a tombstone so the thread
 * can show "message deleted" in place rather than leaving a silent gap, but the
 * content itself is permanently unrecoverable at that point.
 *
 * [imageBytes] holds decrypted JPEG bytes for an IMAGE-kind row and is null
 * otherwise; [body] is empty for IMAGE rows (there's no text alongside a photo in
 * this pass). Note: a data class with a ByteArray field gets reference-based
 * equals/hashCode, not content-based — not an issue today since nothing deep-compares
 * Message (LazyColumn keys on [id]), but worth remembering if that ever changes.
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
    val kind: MessageKind = MessageKind.TEXT,
    val imageBytes: ByteArray? = null,
)
