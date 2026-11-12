package net.spritzlive.securemessenger.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAP_KEY_ALIAS = "securemessenger_signal_identity_wrap"
private const val GCM_TAG_BITS = 128

/** The one-time prekey generated alongside the identity at install. Every bundle used
 * to advertise it; it now lives in the one-time pool like any other and is used once. */
const val FIXED_PREKEY_ID = 1
const val FIXED_SIGNED_PREKEY_ID = 1
const val FIXED_KYBER_PREKEY_ID = 1
const val LOCAL_DEVICE_ID = 1

// libsignal prekey ids are 24-bit ("Medium") values.
private const val MAX_PREKEY_ID = 0xFFFFFF

// Unused one-time prekeys accumulate each time a pairing code is shown but nobody
// pairs against it; keep the newest this many.
private const val MAX_POOL_SIZE = 50

/**
 * This device's Signal identity, signed prekey and Kyber prekey, persisted under
 * AndroidKeyStore envelope encryption (same pattern as RelayAuthKeyStore, its own
 * wrap-key alias).
 *
 * One-time EC prekeys are different: each pairing gets a fresh one
 * ([newPairingBundle]), kept in [OneTimePreKeyPool] and deleted for good once a
 * contact's first message consumes it. Before multiple contacts, every pairing
 * advertised the same fixed prekey, and since this store is rebuilt from disk each
 * launch, libsignal's "remove after use" never stuck — fine with one contact,
 * but a second contact would reuse a one-time key the first one already used, which
 * erodes exactly the forward-secrecy property one-time prekeys exist for.
 *
 * The signed prekey and the Kyber prekey stay fixed: they are designed to be reused
 * (the Kyber one acts as a last-resort key; libsignal tracks its base-key reuse itself).
 */
class SignalIdentityStore(context: Context) {
    private val file = File(context.filesDir, "signal_identity.bin")
    private val pool = OneTimePreKeyPool(context)

    val store: InMemorySignalProtocolStore
    val registrationId: Int

    init {
        if (!file.exists()) generateAndPersist()
        val loaded = loadAndUnwrap()
        registrationId = loaded.registrationId
        // The install-time prekey becomes the pool's first entry, once, so its
        // eventual removal is persisted like any other.
        pool.seedIfAbsent(loaded.preKeyRecord)
        store = PooledPreKeyProtocolStore(loaded.identityKeyPair, loaded.registrationId, pool)
        store.storeSignedPreKey(FIXED_SIGNED_PREKEY_ID, loaded.signedPreKeyRecord)
        store.storeKyberPreKey(FIXED_KYBER_PREKEY_ID, loaded.kyberPreKeyRecord)
    }

    /** A bundle carrying a brand-new one-time prekey. Call once per pairing attempt
     * (each QR shown / each voice-code publish), not per recomposition. */
    fun newPairingBundle(): PreKeyBundle {
        val preKeyRecord = pool.generate()
        val signedPreKeyRecord = store.loadSignedPreKey(FIXED_SIGNED_PREKEY_ID)
        val kyberPreKeyRecord = store.loadKyberPreKey(FIXED_KYBER_PREKEY_ID)
        return PreKeyBundle(
            registrationId,
            LOCAL_DEVICE_ID,
            preKeyRecord.id,
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

        val plaintext = packBlocks(
            identityKeyPair.serialize(),
            intToBytes(registrationId),
            preKeyRecord.serialize(),
            signedPreKeyRecord.serialize(),
            kyberPreKeyRecord.serialize(),
        )
        file.writeBytes(wrap(plaintext))
        plaintext.wipe()
    }

    private fun loadAndUnwrap(): Loaded {
        val plaintext = unwrap(file.readBytes())
        val reader = BlockReader(plaintext)
        val identityKeyPair = IdentityKeyPair(reader.next())
        val registrationId = bytesToInt(reader.next())
        val preKeyRecord = PreKeyRecord(reader.next())
        val signedPreKeyRecord = SignedPreKeyRecord(reader.next())
        val kyberPreKeyRecord = KyberPreKeyRecord(reader.next())
        plaintext.wipe()

        return Loaded(identityKeyPair, registrationId, preKeyRecord, signedPreKeyRecord, kyberPreKeyRecord)
    }
}

/**
 * Routes one-time prekey lookups to [OneTimePreKeyPool] on disk instead of memory.
 * That matters because the pairing screen and the relay service each build their
 * own store: a prekey generated for a QR on the pairing screen must be findable by
 * the service's store when the new contact's first message arrives, and its removal
 * must survive restarts.
 */
private class PooledPreKeyProtocolStore(
    identityKeyPair: IdentityKeyPair,
    registrationId: Int,
    private val pool: OneTimePreKeyPool,
) : InMemorySignalProtocolStore(identityKeyPair, registrationId) {
    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        pool.get(preKeyId) ?: throw InvalidKeyIdException("No such prekey: $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = pool.put(record)

    override fun containsPreKey(preKeyId: Int): Boolean = pool.get(preKeyId) != null

    override fun removePreKey(preKeyId: Int) = pool.remove(preKeyId)
}

/** Shared across every instance in the process: the pool file is read-modify-write. */
private val poolLock = Any()

/** Keystore-wrapped file of unused one-time prekeys, oldest first. */
internal class OneTimePreKeyPool(context: Context) {
    private val file = File(context.filesDir, "signal_onetime_prekeys.bin")
    private val random = SecureRandom()

    fun seedIfAbsent(record: PreKeyRecord) = synchronized(poolLock) {
        if (!file.exists()) save(linkedMapOf(record.id to record))
    }

    fun get(id: Int): PreKeyRecord? = synchronized(poolLock) { load()[id] }

    fun put(record: PreKeyRecord) = synchronized(poolLock) {
        val records = load()
        records.remove(record.id)
        records[record.id] = record
        while (records.size > MAX_POOL_SIZE) records.remove(records.keys.first())
        save(records)
    }

    fun remove(id: Int) = synchronized(poolLock) {
        val records = load()
        if (records.remove(id) != null) save(records)
    }

    fun generate(): PreKeyRecord = synchronized(poolLock) {
        val records = load()
        var id: Int
        do {
            id = 2 + random.nextInt(MAX_PREKEY_ID - 1) // never 0, never the install-time id 1
        } while (records.containsKey(id))
        val record = PreKeyRecord(id, ECKeyPair.generate())
        put(record)
        record
    }

    private fun load(): LinkedHashMap<Int, PreKeyRecord> {
        val records = LinkedHashMap<Int, PreKeyRecord>()
        if (!file.exists()) return records
        val plaintext = unwrap(file.readBytes())
        val reader = BlockReader(plaintext)
        while (reader.hasNext()) {
            val record = PreKeyRecord(reader.next())
            records[record.id] = record
        }
        plaintext.wipe()
        return records
    }

    private fun save(records: Map<Int, PreKeyRecord>) {
        val plaintext = packBlocks(*records.values.map { it.serialize() }.toTypedArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(wrap(plaintext))
        plaintext.wipe()
        if (!tmp.renameTo(file)) error("couldn't replace one-time prekey pool")
    }
}

private fun wrap(plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
    return packBlocks(cipher.iv, cipher.doFinal(plaintext))
}

private fun unwrap(stored: ByteArray): ByteArray {
    val reader = BlockReader(stored)
    val iv = reader.next()
    val wrapped = reader.next()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
    return cipher.doFinal(wrapped)
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

private fun packBlocks(vararg blocks: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (block in blocks) {
        val len = block.size
        out.write(len ushr 24)
        out.write(len ushr 16)
        out.write(len ushr 8)
        out.write(len)
        out.write(block)
    }
    return out.toByteArray()
}

private class BlockReader(private val bytes: ByteArray) {
    private var offset = 0
    fun hasNext(): Boolean = offset < bytes.size
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
