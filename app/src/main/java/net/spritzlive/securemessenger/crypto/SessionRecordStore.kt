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
private const val LEGACY_FILE = "signal_session.bin"
private val PEER_ID = Regex("^[0-9a-f]{64}$")

/**
 * Persists one contact's Double Ratchet `SessionRecord` bytes, so the ratchet chain
 * continues across app restarts instead of re-running a full PQXDH handshake every
 * launch (which would also burn a fresh one-time prekey each time).
 *
 * One file per contact, named by peerId. Before multiple contacts this was a single
 * `signal_session.bin`; [migrateLegacy] renames that to the original contact's file.
 * AndroidKeystore-wrapped like the other local crypto material here.
 */
class SessionRecordStore(context: Context, peerId: String) {
    init {
        // peerId becomes part of a file name: only ever a hex SHA-256.
        require(PEER_ID.matches(peerId)) { "invalid peerId" }
    }

    private val file = File(context.filesDir, "signal_session_$peerId.bin")

    companion object {
        /** Gives the pre-multi-contact session file to the contact it belonged to.
         * Idempotent; safe to call on every launch. */
        fun migrateLegacy(context: Context, peerId: String) {
            require(PEER_ID.matches(peerId)) { "invalid peerId" }
            val legacy = File(context.filesDir, LEGACY_FILE)
            if (!legacy.exists()) return
            val target = File(context.filesDir, "signal_session_$peerId.bin")
            if (target.exists()) {
                legacy.delete()
            } else if (!legacy.renameTo(target)) {
                error("couldn't migrate session file")
            }
        }
    }

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
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(packBlocks(iv, wrapped))
        if (!tmp.renameTo(file)) error("couldn't save session")
    }

    fun delete() {
        file.delete()
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
