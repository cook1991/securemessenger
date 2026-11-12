package net.spritzlive.securemessenger.pairing

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.spritzlive.securemessenger.crypto.SessionRecordStore
import java.io.File

enum class VerificationMethod { QR, VOICE }

/**
 * Everything here is the PEER's public key material plus a display name — not
 * secret, unlike our own private keys in RelayAuthKeyStore/SignalIdentityStore — so
 * plain JSON on local storage (already sandboxed by Android's per-app storage) is
 * appropriate; no AndroidKeystore wrapping needed.
 *
 * [scannedPayloadB64] caches the peer's exact QrPairing-encoded payload from
 * pairing time (re-decodable via QrPairing.decode) so the first message can be
 * encrypted without a fresh fetch.
 */
@Serializable
data class VerifiedContact(
    val peerId: String,
    val displayName: String,
    val identityPublicKeyB64: String,
    val scannedPayloadB64: String,
    val verifiedAt: Long,
    val verificationMethod: VerificationMethod,
)

/** Every store instance in the process shares one file; writes are read-modify-write. */
private val contactsLock = Any()

/**
 * The verified contacts, keyed by peerId. Before multiple contacts this was a
 * single-slot `verified_contact.json`; the first access migrates it (and its
 * session file) into the list, so the existing conversation carries over with no
 * re-pairing.
 */
class ContactVerificationStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "contacts.json")
    private val legacyFile = File(appContext.filesDir, "verified_contact.json")

    init {
        migrateLegacy()
    }

    fun loadAll(): List<VerifiedContact> = synchronized(contactsLock) { read() }

    fun get(peerId: String): VerifiedContact? = loadAll().firstOrNull { it.peerId == peerId }

    /** Adds [contact], or replaces the existing entry with the same peerId. */
    fun save(contact: VerifiedContact) = synchronized(contactsLock) {
        write(read().filterNot { it.peerId == contact.peerId } + contact)
    }

    fun remove(peerId: String) = synchronized(contactsLock) {
        write(read().filterNot { it.peerId == peerId })
    }

    private fun read(): List<VerifiedContact> {
        if (!file.exists()) return emptyList()
        return Json.decodeFromString<List<VerifiedContact>>(file.readText())
    }

    private fun write(contacts: List<VerifiedContact>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.encodeToString(contacts))
        if (!tmp.renameTo(file)) error("couldn't save contacts")
    }

    // Each step is safe to repeat, so an interruption part-way is finished next launch.
    private fun migrateLegacy() {
        synchronized(contactsLock) {
            if (!legacyFile.exists()) return
            val legacy = Json.decodeFromString<VerifiedContact>(legacyFile.readText())
            SessionRecordStore.migrateLegacy(appContext, legacy.peerId)
            if (read().none { it.peerId == legacy.peerId }) write(read() + legacy)
            legacyFile.delete()
        }
    }
}

/**
 * This device's own display name, shown to the peer in the pairing QR. Every
 * install of this same APK is identical code — nothing distinguishes "this is
 * Jason's phone" from "this is Eric's phone" except whatever name gets typed in
 * here once, locally.
 */
class LocalProfileStore(context: Context) {
    private val file = File(context.filesDir, "local_profile.json")

    fun loadDisplayName(): String? {
        if (!file.exists()) return null
        return file.readText().ifBlank { null }
    }

    fun saveDisplayName(name: String) {
        file.writeText(name)
    }
}
