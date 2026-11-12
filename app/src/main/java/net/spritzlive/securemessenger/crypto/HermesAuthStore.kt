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
private const val WRAP_KEY_ALIAS = "securemessenger_hermes_wrap"
private const val GCM_TAG_BITS = 128

/**
 * Keystore-backed storage for the Hermes device token and the last processed
 * message id (for `resume`) — same envelope-encryption shape as RelayAuthKeyStore
 * (hardware-backed AES-GCM wrap key, length-prefixed blocks in a plain file under
 * filesDir), used here instead of EncryptedSharedPreferences so this app doesn't
 * end up with two different idioms for "store my most sensitive secret." A
 * dedicated Keystore alias, separate from RelayAuthKeyStore's and the Signal
 * identity's, keeps this credential's wrap key independent of either.
 *
 * The HMAC signing key itself is never persisted here — HermesHmac derives it from
 * the device token on demand each time it's needed, so there's one fewer secret at
 * rest.
 */
class HermesAuthStore(context: Context) {
    private val file = File(context.filesDir, "hermes_auth.bin")

    /** Whether pairing has completed, without decrypting the token. */
    fun isPaired(): Boolean = file.exists()

    /** Null until pairing has completed. */
    fun loadDeviceToken(): ByteArray? {
        val state = loadState() ?: return null
        return state.token
    }

    fun saveDeviceToken(token: ByteArray) {
        persist(token, loadState()?.lastId)
    }

    /** Null until at least one inbound `message` frame has been processed. */
    fun loadLastId(): String? = loadState()?.lastId

    fun saveLastId(lastId: String) {
        val token = loadState()?.token ?: return
        persist(token, lastId)
    }

    fun clear() {
        if (file.exists()) file.delete()
    }

    private data class State(val token: ByteArray, val lastId: String?)

    private fun loadState(): State? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        var offset = 0
        fun readBlock(): ByteArray {
            val len = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            val block = bytes.copyOfRange(offset, offset + len)
            offset += len
            return block
        }
        val iv = readBlock()
        val wrappedPayload = readBlock()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val payload = cipher.doFinal(wrappedPayload)

        var p = 0
        fun readPayloadBlock(): ByteArray {
            val len = ((payload[p].toInt() and 0xFF) shl 24) or
                ((payload[p + 1].toInt() and 0xFF) shl 16) or
                ((payload[p + 2].toInt() and 0xFF) shl 8) or
                (payload[p + 3].toInt() and 0xFF)
            p += 4
            val block = payload.copyOfRange(p, p + len)
            p += len
            return block
        }
        val token = readPayloadBlock()
        val lastIdBytes = readPayloadBlock()
        val lastId = if (lastIdBytes.isEmpty()) null else String(lastIdBytes, Charsets.UTF_8)
        return State(token, lastId)
    }

    private fun persist(token: ByteArray, lastId: String?) {
        val lastIdBytes = lastId?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val payload = ArrayList<Byte>()
        writeBlock(payload, token)
        writeBlock(payload, lastIdBytes)
        val payloadBytes = payload.toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val iv = cipher.iv
        val wrapped = cipher.doFinal(payloadBytes)
        payloadBytes.wipe()

        val out = ArrayList<Byte>()
        writeBlock(out, iv)
        writeBlock(out, wrapped)
        file.writeBytes(out.toByteArray())
    }

    private fun writeBlock(out: ArrayList<Byte>, bytes: ByteArray) {
        val len = bytes.size
        out.add((len ushr 24).toByte())
        out.add((len ushr 16).toByte())
        out.add((len ushr 8).toByte())
        out.add(len.toByte())
        bytes.forEach { out.add(it) }
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
