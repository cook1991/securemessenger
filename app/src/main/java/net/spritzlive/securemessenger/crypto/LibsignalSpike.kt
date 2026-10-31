package net.spritzlive.securemessenger.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import org.signal.libsignal.protocol.util.Medium
import kotlin.random.Random

/**
 * Phase 1 risk spike ONLY — not the app's real session/storage layer (that comes in
 * Phase 3+, backed by AndroidKeystore rather than these in-memory stores). The one
 * thing this proves: libsignal's native (Rust, via JNI) implementation actually
 * loads and runs a real PQXDH handshake + Double Ratchet round trip on this specific
 * device's ABI — something that can silently fail on a real phone even when it works
 * fine on a desktop JVM or an x86_64 emulator, because of ABI packaging mistakes.
 *
 * Uses libsignal's own shipped `InMemorySignalProtocolStore` (production code, not a
 * test double) — mirrors the library's own SessionBuilderTest pattern for PQXDH.
 */
object LibsignalSpike {

    fun runRoundTrip(): Result<String> = runCatching {
        val jasonAddress = SignalProtocolAddress("jason", 1)
        val ericAddress = SignalProtocolAddress("eric", 1)

        val jasonStore = InMemorySignalProtocolStore(generateIdentity(), KeyHelper.generateRegistrationId(false))
        val ericStore = InMemorySignalProtocolStore(generateIdentity(), KeyHelper.generateRegistrationId(false))

        val ericBundle = createPreKeyBundle(ericStore)

        // The PQXDH handshake: Jason processes Eric's published bundle to derive a
        // shared session, without either side needing to be online at the same time.
        SessionBuilder(jasonStore, ericAddress).process(ericBundle)

        val originalMessage = "hello eric, this is jason"
        val jasonCipher = SessionCipher(jasonStore, ericAddress)
        val outgoing: CiphertextMessage = jasonCipher.encrypt(originalMessage.toByteArray())
        check(outgoing.type == CiphertextMessage.PREKEY_TYPE) { "expected PreKey message for first send" }

        val ericCipher = SessionCipher(ericStore, jasonAddress)
        val decrypted = ericCipher.decrypt(PreKeySignalMessage(outgoing.serialize()))
        check(String(decrypted) == originalMessage) { "round-trip plaintext mismatch (Jason -> Eric)" }

        // Eric's reply rides the Double Ratchet, not a fresh PreKey handshake.
        val reply = "hey jason, got it"
        val ericReply = ericCipher.encrypt(reply.toByteArray())
        check(ericReply.type == CiphertextMessage.WHISPER_TYPE) { "expected ordinary ratchet message for reply" }

        val jasonDecryptedReply = jasonCipher.decrypt(SignalMessage(ericReply.serialize()))
        check(String(jasonDecryptedReply) == reply) { "round-trip plaintext mismatch (Eric -> Jason)" }

        "OK on ABI ${System.getProperty("os.arch")} — PQXDH handshake + Double Ratchet round trip both directions"
    }

    private fun generateIdentity(): IdentityKeyPair {
        val keyPair = ECKeyPair.generate()
        return IdentityKeyPair(IdentityKey(keyPair.publicKey), keyPair.privateKey)
    }

    private fun createPreKeyBundle(store: InMemorySignalProtocolStore): PreKeyBundle {
        val preKeyPair = ECKeyPair.generate()
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature = store.identityKeyPair.privateKey
            .calculateSignature(signedPreKeyPair.publicKey.serialize())
        val kyberPreKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPreKeySignature = store.identityKeyPair.privateKey
            .calculateSignature(kyberPreKeyPair.publicKey.serialize())

        val preKeyId = Random.nextInt(Medium.MAX_VALUE)
        val signedPreKeyId = Random.nextInt(Medium.MAX_VALUE)
        val kyberPreKeyId = Random.nextInt(Medium.MAX_VALUE)

        store.storePreKey(preKeyId, PreKeyRecord(preKeyId, preKeyPair))
        store.storeSignedPreKey(
            signedPreKeyId,
            SignedPreKeyRecord(signedPreKeyId, System.currentTimeMillis(), signedPreKeyPair, signedPreKeySignature),
        )
        store.storeKyberPreKey(
            kyberPreKeyId,
            KyberPreKeyRecord(kyberPreKeyId, System.currentTimeMillis(), kyberPreKeyPair, kyberPreKeySignature),
        )

        return PreKeyBundle(
            store.localRegistrationId,
            1,
            preKeyId,
            preKeyPair.publicKey,
            signedPreKeyId,
            signedPreKeyPair.publicKey,
            signedPreKeySignature,
            store.identityKeyPair.publicKey,
            kyberPreKeyId,
            kyberPreKeyPair.publicKey,
            kyberPreKeySignature,
        )
    }
}
