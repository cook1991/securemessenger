package net.spritzlive.securemessenger.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAP_KEY_ALIAS = "securemessenger_relay_auth_wrap"
private const val GCM_TAG_BITS = 128

/**
 * Ed25519 keypair used ONLY to authenticate this device to the relay — deliberately
 * separate from the Signal identity key (SessionManager/IdentityKeyStore), so "may
 * this device talk to my relay" stays cleanly independent of "is this device
 * cryptographically Eric."
 *
 * Uses BouncyCastle's own Ed25519 implementation, NOT the platform's java.security
 * "Ed25519" provider: confirmed on real hardware that platform support is
 * inconsistent across Android versions (works via Conscrypt on Android 14, but
 * `NoSuchAlgorithmException: Ed25519 KeyPairGenerator not available` on Android 11),
 * so this can't depend on whichever Conscrypt version a given OS build ships.
 * BouncyCastle's output is still standard RFC 8032 Ed25519 — identical wire format,
 * interoperable with the relay's Node-side `crypto.verify`.
 *
 * The private key's raw 32-byte seed is still never written to disk in recoverable
 * form: it's wrapped under a hardware-backed AndroidKeyStore AES-GCM key before
 * being persisted, the same envelope-encryption pattern IdentityKeyStore uses.
 */
class RelayAuthKeyStore(context: Context) {
    private val file = File(context.filesDir, "relay_auth_identity.bin")

    val publicKeyRaw: ByteArray
    private val privateKey: Ed25519PrivateKeyParameters

    val peerId: String get() = sha256Hex(publicKeyRaw)

    init {
        if (!file.exists()) generateAndPersist()
        val (pub, priv) = loadAndUnwrap()
        publicKeyRaw = pub
        privateKey = priv
    }

    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun generateAndPersist() {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        val pub = priv.generatePublicKey().encoded

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val iv = cipher.iv
        val wrappedSeed = cipher.doFinal(seed)
        seed.wipe()

        val out = ArrayList<Byte>()
        writeBlock(out, iv)
        writeBlock(out, wrappedSeed)
        writeBlock(out, pub)
        file.writeBytes(out.toByteArray())
    }

    private fun loadAndUnwrap(): Pair<ByteArray, Ed25519PrivateKeyParameters> {
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
        val wrappedSeed = readBlock()
        val pub = readBlock()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val seed = cipher.doFinal(wrappedSeed)
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        seed.wipe()

        return pub to priv
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

    private fun writeBlock(out: ArrayList<Byte>, bytes: ByteArray) {
        val len = bytes.size
        out.add((len ushr 24).toByte())
        out.add((len ushr 16).toByte())
        out.add((len ushr 8).toByte())
        out.add(len.toByte())
        bytes.forEach { out.add(it) }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
