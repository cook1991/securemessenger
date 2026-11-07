package net.spritzlive.securemessenger.net

/**
 * One app-level byte prepended to the plaintext before Signal-encryption, stripped
 * immediately after Signal-decryption — independent of SessionManager's own wire
 * byte, which distinguishes libsignal's PREKEY/WHISPER ratchet state, not payload
 * content type.
 *
 * Values past TEXT/IMAGE are deliberately left unassigned rather than letting this
 * run out of room for a later feature — costs nothing to reserve now.
 */
object MessageEnvelope {
    const val KIND_TEXT: Byte = 0x01
    const val KIND_IMAGE: Byte = 0x02
    // 0x03.. reserved, unassigned.

    fun wrap(kind: Byte, payload: ByteArray): ByteArray = byteArrayOf(kind) + payload

    fun unwrap(envelope: ByteArray): Pair<Byte, ByteArray> =
        envelope[0] to envelope.copyOfRange(1, envelope.size)
}
