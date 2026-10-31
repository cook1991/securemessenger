package net.spritzlive.securemessenger.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val WRAP_KEY_ALIAS = "securemessenger_message_wrap"
private const val GCM_TAG_BITS = 128

/**
 * One AndroidKeystore-backed AES-256-GCM root key, hardware-backed and never
 * exportable, wraps a fresh random AES-256 key generated per message. The message
 * body itself is encrypted under that per-message key, not the root key directly —
 * see CryptoShredRepository.
 *
 * [destroyKey] deletes the wrapped-key row from `message_keys`; that is the actual
 * crypto-shred (the ciphertext for that message becomes permanently undecryptable
 * once its wrapped key is gone, whether or not the SQLite file's underlying flash
 * pages still physically hold the old bytes — flash doesn't reliably erase on
 * delete, which is exactly why this scheme destroys the *key* instead of trying to
 * scrub the ciphertext).
 *
 * Honest limitation, stated plainly: this shares ONE root wrap key across all
 * messages (per the approved plan's "wrapped under a root key" design, not a
 * separate AndroidKeystore entry per message). If an attacker recovers a *stale*
 * wrapped-key blob via raw flash forensics (SQLite doesn't force-overwrite deleted
 * pages) AND separately gains live code-execution with Keystore access on this same
 * device while the root key still exists, that stale blob could in theory still be
 * unwrapped. This does not threaten the realistic target of this design — offline
 * forensic imaging of a seized/lost phone, where the TEE-backed root key is not
 * reachable without the device unlocked and running — only the narrower case of
 * live compromise combined with forensic recovery of already-deleted bytes.
 */
class MessageKeyStore(private val db: MessageDatabase) {

    /** Generates, wraps, and persists a new per-message key; returns (keyId, plaintext key bytes). */
    fun generateAndStoreKey(): Pair<String, ByteArray> {
        val keyId = UUID.randomUUID().toString()
        val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        val wrapIv = cipher.iv
        val wrappedKey = cipher.doFinal(keyBytes)

        db.insertKey(keyId, wrapIv, wrappedKey)
        return keyId to keyBytes
    }

    /** Null means the key has already been shredded (row gone) — caller should treat the message as a tombstone. */
    fun unwrapKey(keyId: String): ByteArray? {
        val row = db.loadKey(keyId) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, row.wrapIv))
        return cipher.doFinal(row.wrappedKey)
    }

    fun destroyKey(keyId: String) {
        db.deleteKey(keyId)
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
