package net.spritzlive.securemessenger.net

import net.spritzlive.securemessenger.crypto.LOCAL_DEVICE_ID
import net.spritzlive.securemessenger.crypto.SessionRecordStore
import net.spritzlive.securemessenger.crypto.SignalIdentityStore
import net.spritzlive.securemessenger.crypto.wipe
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle

private const val WIRE_TYPE_PREKEY: Byte = 1
private const val WIRE_TYPE_WHISPER: Byte = 2

/**
 * The real session/transport crypto layer, replacing Phase 3's hardcoded-trust spike.
 * Wraps libsignal's SessionBuilder/SessionCipher for the single MVP peer, restoring
 * persisted ratchet state (SessionRecordStore) on construction and re-persisting it
 * after every encrypt/decrypt, so the chain survives app restarts — see
 * SessionRecordStore's doc for why that matters beyond convenience.
 *
 * Wire format prepends one type byte to the serialized CiphertextMessage, since the
 * receiver needs to know whether to construct a PreKeySignalMessage (first message of
 * a session) or a SignalMessage (ordinary ratchet message) before it can decrypt —
 * libsignal's CiphertextMessage.type distinguishes these but that information isn't
 * otherwise carried in the serialized bytes themselves.
 */
class SessionManager(
    private val identityStore: SignalIdentityStore,
    private val sessionRecordStore: SessionRecordStore,
    remotePeerId: String,
    private val remoteBundle: PreKeyBundle,
) {
    private val store = identityStore.store
    private val remoteAddress = SignalProtocolAddress(remotePeerId, LOCAL_DEVICE_ID)

    init {
        sessionRecordStore.load()?.let { bytes ->
            store.storeSession(remoteAddress, org.signal.libsignal.protocol.state.SessionRecord(bytes))
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

    /** Ownership inverts here versus [encrypt]: this now returns live plaintext
     * bytes the CALLER owns and must wipe once done (previously this class wiped
     * internally before returning a String copy — a String can't be wiped, and an
     * image's plaintext bytes need to survive long enough for the UI to render). */
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
