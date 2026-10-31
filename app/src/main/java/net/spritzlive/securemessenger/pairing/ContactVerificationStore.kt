package net.spritzlive.securemessenger.pairing

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class VerificationMethod { QR, VOICE }

/**
 * Everything here is the PEER's public key material plus a display name — not
 * secret, unlike our own private keys in RelayAuthKeyStore/SignalIdentityStore — so
 * plain JSON on local storage (already sandboxed by Android's per-app storage) is
 * appropriate; no AndroidKeystore wrapping needed.
 *
 * [scannedPayloadB64] caches the peer's exact QrPairing-encoded QR payload from
 * pairing time (re-decodable via QrPairing.decode) so the first message can be
 * encrypted without a fresh fetch. This app's prekey set doesn't rotate yet
 * (SignalIdentityStore is a fixed single set, not a real pool — Phase 5+ territory),
 * so there's no "refresh the bundle" path to fall back on yet; once that exists,
 * revisit whether caching it this long still makes sense.
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

/**
 * This app is 1:1 only (Jason + Eric, per the plan) — so this is deliberately a
 * single-slot store, not a contact list.
 */
class ContactVerificationStore(context: Context) {
    private val file = File(context.filesDir, "verified_contact.json")

    fun load(): VerifiedContact? {
        if (!file.exists()) return null
        return Json.decodeFromString<VerifiedContact>(file.readText())
    }

    fun save(contact: VerifiedContact) {
        file.writeText(Json.encodeToString(contact))
    }

    fun clear() {
        file.delete()
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
