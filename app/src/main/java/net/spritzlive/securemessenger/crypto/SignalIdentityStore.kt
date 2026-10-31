package net.spritzlive.securemessenger.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAP_KEY_ALIAS = "securemessenger_signal_identity_wrap"
private const val GCM_TAG_BITS = 128
const val FIXED_PREKEY_ID = 1
const val FIXED_SIGNED_PREKEY_ID = 1
const val FIXED_KYBER_PREKEY_ID = 1
const val LOCAL_DEVICE_ID = 1

/**
 * Phase 3 groundwork — NOT the real Phase 5 session/storage layer (that's backed by
 * the full crypto-shred message database, not this). Persists this device's Signal
 * identity plus ONE set of prekeys (not a rotating pool — that's Phase 5+) under
 * AndroidKeyStore envelope encryption, the same pattern RelayAuthKeyStore uses, with
 * its own separate wrap-key alias so the two key domains never share wrapping key
 * material. Builds a fresh InMemorySignalProtocolStore each run seeded with this
 * persisted material, so the PUBLISHED bundle stays stable across restarts even
 * though in-session ratchet state does not persist yet.
 */
class SignalIdentityStore(context: Context) {
    private val file = File(context.filesDir, "signal_identity.bin")

    val store: InMemorySignalProtocolStore
    val registrationId: Int

    init {
        if (!file.exists()) generateAndPersist()
        val loaded = loadAndUnwrap()
        registrationId = loaded.registrationId
        store = InMemorySignalProtocolStore(loaded.identityKeyPair, loaded.registrationId)
        store.storePreKey(FIXED_PREKEY_ID, loaded.preKeyRecord)
        store.storeSignedPreKey(FIXED_SIGNED_PREKEY_ID, loaded.signedPreKeyRecord)
        store.storeKyberPreKey(FIXED_KYBER_PREKEY_ID, loaded.kyberPreKeyRecord)
    }

    fun ownBundle(): PreKeyBundle {
        val preKeyRecord = store.loadPreKey(FIXED_PREKEY_ID)
        val signedPreKeyRecord = store.loadSignedPreKey(FIXED_SIGNED_PREKEY_ID)
        val kyberPreKeyRecord = store.loadKyberPreKey(FIXED_KYBER_PREKEY_ID)
        return PreKeyBundle(
            registrationId,
            LOCAL_DEVICE_ID,
            FIXED_PREKEY_ID,
            preKeyRecord.keyPair.publicKey,
            FIXED_SIGNED_PREKEY_ID,
            signedPreKeyRecord.keyPair.publicKey,
            signedPreKeyRecord.signature,
            store.identityKeyPair.publicKey,
            FIXED_KYBER_PREKEY_ID,
            kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeyRecord.signature,
        )
    }

    private data class Loaded(
        val identityKeyPair: IdentityKeyPair,
        val registrationId: Int,
        val preKeyRecord: PreKeyRecord,
        val signedPreKeyRecord: SignedPreKeyRecord,
        val kyberPreKeyRecord: KyberPreKeyRecord,
    )

    private fun generateAndPersist() {
        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)

        val preKeyPair = ECKeyPair.generate()
        val preKeyRecord = PreKeyRecord(FIXED_PREKEY_ID, preKeyPair)

        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature = identityKeyPair.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize())
        val signedPreKeyRecord = SignedPreKeyRecord(
            FIXED_SIGNED_PREKEY_ID,
            System.currentTimeMillis(),
            signedPreKeyPair,
            signedPreKeySignature,
        )

        val kyberPreKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberPreKeySignature = identityKeyPair.privateKey.calculateSignature(kyberPreKeyPair.publicKey.serialize())
        val kyberPreKeyRecord = KyberPreKeyRecord(
            FIXED_KYBER_PREKEY_ID,
            System.currentTimeMillis(),
            kyberPreKeyPair,
            kyberPreKeySignature,
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val iv = cipher.iv
        val plaintext = packBlocks(
            identityKeyPair.serialize(),
            intToBytes(registrationId),
            preKeyRecord.serialize(),
            signedPreKeyRecord.serialize(),
            kyberPreKeyRecord.serialize(),
        )
        val wrapped = cipher.doFinal(plaintext)
        plaintext.wipe()

        file.writeBytes(packBlocks(iv, wrapped))
    }

    private fun loadAndUnwrap(): Loaded {
        val bytes = file.readBytes()
        val outerReader = BlockReader(bytes)
        val iv = outerReader.next()
        val wrapped = outerReader.next()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(wrapped)

        val reader = BlockReader(plaintext)
        val identityKeyPair = IdentityKeyPair(reader.next())
        val registrationId = bytesToInt(reader.next())
        val preKeyRecord = PreKeyRecord(reader.next())
        val signedPreKeyRecord = SignedPreKeyRecord(reader.next())
        val kyberPreKeyRecord = KyberPreKeyRecord(reader.next())
        plaintext.wipe()

        return Loaded(identityKeyPair, registrationId, preKeyRecord, signedPreKeyRecord, kyberPreKeyRecord)
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

private fun packBlocks(vararg blocks: ByteArray): ByteArray {
    val out = ArrayList<Byte>()
    for (block in blocks) {
        val len = block.size
        out.add((len ushr 24).toByte())
        out.add((len ushr 16).toByte())
        out.add((len ushr 8).toByte())
        out.add(len.toByte())
        block.forEach { out.add(it) }
    }
    return out.toByteArray()
}

private class BlockReader(private val bytes: ByteArray) {
    private var offset = 0
    fun next(): ByteArray {
        val len = ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
        offset += 4
        val block = bytes.copyOfRange(offset, offset + len)
        offset += len
        return block
    }
}

private fun intToBytes(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)

private fun bytesToInt(bytes: ByteArray): Int =
    ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)
