package net.spritzlive.securemessenger.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAP_KEY_ALIAS = "securemessenger_session_wrap"
private const val GCM_TAG_BITS = 128

/**
 * Persists the Double Ratchet's `SessionRecord` bytes for the one MVP peer, so the
 * ratchet chain continues across app restarts instead of re-running a full PQXDH
 * handshake every launch. Re-handshaking every launch would also be a real weakness,
 * not just wasteful: the fixed one-time prekey (SignalIdentityStore.FIXED_PREKEY_ID)
 * is meant to be consumed once, and X3DH/PQXDH's one-time-prekey security property
 * depends on that — reusing it across repeated handshakes would quietly erode it.
 *
 * Single-slot, same reasoning as ContactVerificationStore: this app only ever has
 * one peer. AndroidKeystore-wrapped like the other local crypto material here, since
 * ratchet chain keys are exactly the kind of thing this scheme exists to protect.
 */
class SessionRecordStore(context: Context) {
    private val file = File(context.filesDir, "signal_session.bin")

    fun load(): ByteArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val reader = BlockReader(bytes)
        val iv = reader.next()
        val wrapped = reader.next()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(wrapped)
    }

    fun save(sessionRecordBytes: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val iv = cipher.iv
        val wrapped = cipher.doFinal(sessionRecordBytes)
        file.writeBytes(packBlocks(iv, wrapped))
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
}
