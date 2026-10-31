package net.spritzlive.securemessenger.pairing

import android.content.Context
import net.spritzlive.securemessenger.crypto.RelayAuthKeyStore
import net.spritzlive.securemessenger.crypto.SignalIdentityStore
import net.spritzlive.securemessenger.net.RelayClient
import net.spritzlive.securemessenger.net.RelayConfig

/**
 * Remote pairing when QR's physical proximity isn't available — the one piece the
 * plan explicitly flagged as not-yet-built (see QrPairing's own class doc). Reuses
 * QrPairing's existing encode/decode entirely unchanged: the packed-bytes format
 * doesn't care whether it travels through a camera or a relay round-trip.
 *
 * The peerId exchange itself (typing in the other person's relayPeerId) happens over
 * an ordinary side channel — a phone call or text — same as Signal's own phone-number
 * model: public identifiers in the clear, with the spoken safety-number fingerprint
 * comparison (identical algorithm to QR's, see [QrPairing.fingerprintDisplayText]) as
 * the actual authentication step. A relay operator who tampered with a published
 * bundle in transit would produce a mismatched fingerprint, caught by that comparison
 * — same security property QR gets from physical proximity, enforced here by a human
 * reading digits aloud instead.
 *
 * This owns a short-lived RelayClient independent of NetworkRepository/
 * RelayConnectionService, since pairing happens before a verified contact exists —
 * NetworkRepository's constructor requires one and fails loudly otherwise. For a
 * first-time pairing this is the only connection running (RelayConnectionService
 * only ever starts from ThreadScreen, which isn't reachable pre-pairing), so there's
 * no conflict.
 *
 * Known limitation, confirmed on real hardware: using "Re-pair (overwrite)" while
 * already paired opens a SECOND connection registered under the same peerId
 * alongside RelayConnectionService's already-running one. The relay's PeerRegistry
 * keeps only one live socket per peerId (last register wins), so while this screen
 * is open, incoming messages route here instead of to the service — and this class
 * doesn't handle relay-blob, so they're silently dropped rather than queued (the
 * relay still thinks the peer is online). Closing this screen restores normal
 * delivery; anything sent during that window needs a resend. Not a risk for the
 * primary first-time-pairing path, which is what matters for getting a new device
 * paired — worth a proper fix (e.g. routing pairing through the existing connection
 * when one's already live) before this re-pair path sees real everyday use.
 */
class VoiceCodePairing(context: Context) {
    private val relayAuth = RelayAuthKeyStore(context)
    private val signalStore = SignalIdentityStore(context)
    private val relayClient = RelayClient(
        host = RelayConfig.HOST,
        port = RelayConfig.PORT,
        pinSha256Base64 = RelayConfig.PIN_SHA256_B64,
        trustedCertPem = RelayConfig.CERT_PEM,
        auth = relayAuth,
    )

    val myPeerId: String get() = relayAuth.peerId

    var onStateChange: ((String) -> Unit)?
        get() = relayClient.onStateChange
        set(value) { relayClient.onStateChange = value }

    var onRegistered: (() -> Unit)?
        get() = relayClient.onRegistered
        set(value) { relayClient.onRegistered = value }

    var onPeerFound: ((QrPairing.ScannedPeer) -> Unit)? = null
    var onPeerNotFound: ((String) -> Unit)? = null

    init {
        relayClient.onBundle = { _, bundleB64 ->
            runCatching { QrPairing.decode(bundleB64) }
                .onSuccess { onPeerFound?.invoke(it) }
                .onFailure { onStateChange?.invoke("couldn't decode peer's bundle: ${it.message}") }
        }
        relayClient.onBundleNotFound = { peerId -> onPeerNotFound?.invoke(peerId) }
    }

    fun connect() = relayClient.connect()
    fun close() = relayClient.close()

    fun publishMyBundle(displayName: String) {
        relayClient.publishBundle(QrPairing.encode(relayAuth.publicKeyRaw, displayName, signalStore.ownBundle()))
    }

    fun fetchPeerBundle(peerId: String) = relayClient.fetchBundle(peerId)
}
