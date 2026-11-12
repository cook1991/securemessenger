package net.spritzlive.securemessenger.net

import net.spritzlive.securemessenger.crypto.LOCAL_DEVICE_ID
import net.spritzlive.securemessenger.crypto.SessionRecordStore
import net.spritzlive.securemessenger.crypto.wipe
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore

private const val WIRE_TYPE_PREKEY: Byte = 1
private const val WIRE_TYPE_WHISPER: Byte = 2

/**
 * One contact's Signal session: wraps libsignal's SessionBuilder/SessionCipher,
 * restoring persisted ratchet state (SessionRecordStore) on construction and
 * re-persisting it after every encrypt/decrypt, so the chain survives app restarts.
 * Several of these share one protocol store (one per contact address).
 *
 * The contact's identity key from pairing is pinned as the only trusted identity for
 * their address, so libsignal rejects anything signed by a different identity
 * instead of trusting whatever arrives first. A stored session whose remote identity
 * doesn't match (the contact was re-paired) is discarded, so the next send starts a
 * fresh session from the new bundle.
 *
 * Wire format prepends one type byte to the serialized CiphertextMessage, since the
 * receiver needs to know whether to construct a PreKeySignalMessage (first message of
 * a session) or a SignalMessage before it can decrypt.
 *
 * Not thread-safe; NetworkRepository serializes all calls.
 */
class SessionManager(
    private val store: SignalProtocolStore,
    private val sessionRecordStore: SessionRecordStore,
    remotePeerId: String,
    remoteIdentity: IdentityKey,
    private val remoteBundle: PreKeyBundle,
) {
    private val remoteAddress = SignalProtocolAddress(remotePeerId, LOCAL_DEVICE_ID)

    init {
        store.saveIdentity(remoteAddress, remoteIdentity)
        store.deleteSession(remoteAddress)
        val saved = sessionRecordStore.load()?.let { SessionRecord(it) }
        if (saved != null) {
            if (saved.remoteIdentityKey == remoteIdentity) {
                store.storeSession(remoteAddress, saved)
            } else {
                sessionRecordStore.delete()
            }
        }
    }

    /** Wipes [plaintext] before returning, same as always — caller must not reuse it. */
    fun encrypt(plaintext: ByteArray): ByteArray {
        if (!store.containsSession(remoteAddress)) {
            SessionBuilder(store, remoteAddress).process(remoteBundle)
        }
        val cipher = SessionCipher(store, remoteAddress)
        val ciphertextMessage = cipher.encrypt(plaintext)
        plaintext.wipe()
        persistSession()

        val typeTag = if (ciphertextMessage.type == CiphertextMessage.PREKEY_TYPE) WIRE_TYPE_PREKEY else WIRE_TYPE_WHISPER
        return byteArrayOf(typeTag) + ciphertextMessage.serialize()
    }

    /** Returns live plaintext bytes the CALLER owns and must wipe once done. */
    fun decrypt(wireBytes: ByteArray): ByteArray {
        val typeTag = wireBytes[0]
        val body = wireBytes.copyOfRange(1, wireBytes.size)
        val cipher = SessionCipher(store, remoteAddress)
        val plaintextBytes = when (typeTag) {
            WIRE_TYPE_PREKEY -> cipher.decrypt(PreKeySignalMessage(body))
            WIRE_TYPE_WHISPER -> cipher.decrypt(SignalMessage(body))
            else -> error("unknown wire type tag $typeTag")
        }
        persistSession()
        return plaintextBytes
    }

    private fun persistSession() {
        val record = store.loadSession(remoteAddress)
        sessionRecordStore.save(record.serialize())
    }
}
