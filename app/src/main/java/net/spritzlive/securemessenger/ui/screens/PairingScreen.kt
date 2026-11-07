package net.spritzlive.securemessenger.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.spritzlive.securemessenger.crypto.RelayAuthKeyStore
import net.spritzlive.securemessenger.crypto.SignalIdentityStore
import net.spritzlive.securemessenger.net.RelayConnectionService
import net.spritzlive.securemessenger.pairing.ContactVerificationStore
import net.spritzlive.securemessenger.pairing.LocalProfileStore
import net.spritzlive.securemessenger.pairing.QrPairing
import net.spritzlive.securemessenger.pairing.VerificationMethod
import net.spritzlive.securemessenger.pairing.VerifiedContact
import net.spritzlive.securemessenger.pairing.VoiceCodePairing
import android.util.Base64

/**
 * Phase 4: real verified pairing, replacing Phase 3's hardcoded trust
 * (TempCryptoRelayTest's manually-copied bundle constants, now deleted).
 *
 * [onBack] is null when this is the app's start destination (nothing paired yet,
 * nowhere to go back to) and non-null when reached from ThreadScreen's top bar
 * (contact info / re-pair / safety number, with a way back to the conversation).
 */
@Composable
fun PairingScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val contactStore = remember { ContactVerificationStore(context) }
    val profileStore = remember { LocalProfileStore(context) }
    val relayAuth = remember { RelayAuthKeyStore(context) }
    val signalStore = remember { SignalIdentityStore(context) }

    var myDisplayName by rememberSaveable { mutableStateOf(profileStore.loadDisplayName() ?: "") }
    var nameInput by rememberSaveable { mutableStateOf(myDisplayName) }
    var verifiedContact by remember { mutableStateOf(contactStore.load()) }

    var scannedPeer by remember { mutableStateOf<QrPairing.ScannedPeer?>(null) }
    var scannedPayload by remember { mutableStateOf<String?>(null) }
    var fingerprintText by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }

    var pairingMode by rememberSaveable { mutableStateOf("choose") } // "choose" | "qr" | "voice"
    val clipboard = LocalClipboardManager.current

    // Only built/connected while the voice-pairing section is actually open — no
    // reason to hold a second relay socket open the rest of the time.
    val voicePairing = remember(pairingMode) {
        if (pairingMode == "voice") VoiceCodePairing(context) else null
    }
    // Keyed to voicePairing (not bare remember): a fresh VoiceCodePairing means a
    // fresh relay connection that hasn't published/fetched anything yet, so this
    // state must reset alongside it — otherwise leaving and re-entering voice mode
    // left a stale "Published"/disabled button pointing at a connection that was
    // never actually told to publish.
    var voiceStatus by remember(voicePairing) { mutableStateOf("connecting…") }
    var voiceRegistered by remember(voicePairing) { mutableStateOf(false) }
    var voicePublished by remember(voicePairing) { mutableStateOf(false) }
    var peerIdInput by rememberSaveable { mutableStateOf("") }
    var voiceNotFound by remember(voicePairing) { mutableStateOf(false) }
    var voicePeer by remember(voicePairing) { mutableStateOf<QrPairing.ScannedPeer?>(null) }
    var voiceFingerprintText by remember(voicePairing) { mutableStateOf<String?>(null) }

    DisposableEffect(voicePairing) {
        val vp = voicePairing
        if (vp != null) {
            // The main app connection (RelayConnectionService) registers under this
            // device's peerId too, and the relay keeps only the most recent
            // registration live — without stopping it first, this second connection
            // would silently steal incoming messages instead of the relay queuing
            // them (see VoiceCodePairing's class doc). No-op if that service isn't
            // running, which covers the primary first-time-pairing path.
            context.stopService(Intent(context, RelayConnectionService::class.java))
            vp.onStateChange = { voiceStatus = it }
            vp.onRegistered = { voiceRegistered = true }
            vp.onPeerFound = { peer ->
                voiceNotFound = false
                voicePeer = peer
                voiceFingerprintText = QrPairing.fingerprintDisplayText(
                    relayAuth.peerId,
                    signalStore.store.identityKeyPair.publicKey,
                    peer.relayPeerId,
                    peer.bundle.identityKey,
                )
            }
            vp.onPeerNotFound = { voiceNotFound = true }
            vp.connect()
        }
        onDispose { vp?.close() }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        // contents (text), not rawBytes: rawBytes turned out to be zxing's whole raw
        // codeword bitstream (mode/length/ECI framing included), not a clean
        // extraction of this payload — see QrPairing's class doc for how that was
        // confirmed. contents is the correctly-extracted ISO-8859-1 text.
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        runCatching { QrPairing.decodeFromQrText(contents) }
            .onSuccess { peer ->
                scanError = null
                scannedPeer = peer
                scannedPayload = Base64.encodeToString(contents.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
                fingerprintText = QrPairing.fingerprintDisplayText(
                    relayAuth.peerId,
                    signalStore.store.identityKeyPair.publicKey,
                    peer.relayPeerId,
                    peer.bundle.identityKey,
                )
            }
            .onFailure {
                scannedPeer = null
                scanError = "Couldn't read that code: ${it.message}"
            }
    }

    // The "Scanned"/"Found" confirmation card is the last thing added to the
    // column when a peer is found, which put it below the fold on real devices
    // and caused real confusion during live pairing (looked like the fetch had
    // silently failed) — see PairingScreen's known-follow-up note. Scroll it
    // into view instead of leaving it to the user to discover by scrolling.
    val scrollState = rememberScrollState()
    LaunchedEffect(scannedPeer) {
        if (scannedPeer != null) scrollState.animateScrollTo(scrollState.maxValue)
    }
    LaunchedEffect(voicePeer) {
        if (voicePeer != null) scrollState.animateScrollTo(scrollState.maxValue)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        } else {
            scanError = "Camera permission is required to scan a pairing code."
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Pairing")

        if (myDisplayName.isBlank()) {
            Text("What's your name? (shown to the other person when they scan your code)")
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text("Your name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val trimmed = nameInput.trim()
                    if (trimmed.isNotEmpty()) {
                        profileStore.saveDisplayName(trimmed)
                        myDisplayName = trimmed
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            return@Column
        }

        if (verifiedContact != null) {
            val contact = verifiedContact!!
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paired with ${contact.displayName}")
                    Text("Verified via ${contact.verificationMethod}")
                    Text(
                        QrPairing.fingerprintDisplayText(
                            relayAuth.peerId,
                            signalStore.store.identityKeyPair.publicKey,
                            contact.peerId,
                            org.signal.libsignal.protocol.IdentityKey(
                                Base64.decode(contact.identityPublicKeyB64, Base64.NO_WRAP),
                            ),
                        ),
                    )
                }
            }
            TextButton(onClick = { verifiedContact = null }) { Text("Re-pair (overwrite)") }
            return@Column
        }

        if (pairingMode == "choose") {
            Text("Pairing in person? Use QR. Pairing with someone remote (on a call)? Use the voice code.")
            Button(onClick = { pairingMode = "qr" }, modifier = Modifier.fillMaxWidth()) {
                Text("Pair in person (QR)")
            }
            Button(onClick = { pairingMode = "voice" }, modifier = Modifier.fillMaxWidth()) {
                Text("Pair remotely (voice code)")
            }
        }

        if (pairingMode == "qr") {
            TextButton(onClick = { pairingMode = "choose" }) { Text("← Back") }
            Text("Your code — have ${if (myDisplayName.isNotBlank()) "them" else "the other person"} scan this")
            val myQrBitmap = remember(myDisplayName) {
                QrPairing.generateQrBitmap(relayAuth.publicKeyRaw, myDisplayName, signalStore.ownBundle())
            }
            // Displayed as large as the screen allows, at nearest-neighbor quality: a
            // ~150-module-dense QR (this payload's size, dominated by the Kyber-1024 key)
            // needs every module to be as many physical screen pixels as possible for a
            // phone camera to resolve it — confirmed on real hardware (BLU G91S) that the
            // smaller fixed size this used before was not reliably scannable.
            Image(
                bitmap = myQrBitmap.asImageBitmap(),
                contentDescription = "Your pairing QR code",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            )

            Button(
                onClick = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan their code") }

            scanError?.let { Text(it) }

            scannedPeer?.let { peer ->
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Scanned: ${peer.displayName}")
                        Text("Safety number:")
                        Text(fingerprintText ?: "")
                        Text("Confirm this matches what they see on their screen, then save.")
                        Button(
                            onClick = {
                                val saved = VerifiedContact(
                                    peerId = peer.relayPeerId,
                                    displayName = peer.displayName,
                                    identityPublicKeyB64 = Base64.encodeToString(
                                        peer.bundle.identityKey.serialize(),
                                        Base64.NO_WRAP,
                                    ),
                                    scannedPayloadB64 = scannedPayload ?: "",
                                    verifiedAt = System.currentTimeMillis(),
                                    verificationMethod = VerificationMethod.QR,
                                )
                                contactStore.save(saved)
                                verifiedContact = saved
                                scannedPeer = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Confirm & save") }
                    }
                }
            }
        }

        if (pairingMode == "voice" && voicePairing != null) {
            TextButton(onClick = { pairingMode = "choose" }) { Text("← Back") }
            Text("Status: $voiceStatus")

            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your code (read or text this to them):")
                    Text(voicePairing.myPeerId)
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(voicePairing.myPeerId)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Copy my code") }
                }
            }

            Button(
                enabled = voiceRegistered && !voicePublished,
                onClick = {
                    voicePairing.publishMyBundle(myDisplayName)
                    voicePublished = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (voicePublished) "Published — ready for them to fetch" else "1. Publish my info") }

            OutlinedTextField(
                value = peerIdInput,
                onValueChange = { peerIdInput = it.trim() },
                label = { Text("Their code") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = voiceRegistered && peerIdInput.isNotBlank(),
                onClick = {
                    voiceNotFound = false
                    voicePeer = null
                    voicePairing.fetchPeerBundle(peerIdInput)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("2. Fetch & verify") }

            if (voiceNotFound) {
                Text("Not published yet — ask them to tap \"Publish my info\" first, then try again.")
            }

            voicePeer?.let { peer ->
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Found: ${peer.displayName}")
                        Text("Safety number — read this aloud and confirm it matches what they read back:")
                        Text(voiceFingerprintText ?: "")
                        Button(
                            onClick = {
                                val saved = VerifiedContact(
                                    peerId = peer.relayPeerId,
                                    displayName = peer.displayName,
                                    identityPublicKeyB64 = Base64.encodeToString(
                                        peer.bundle.identityKey.serialize(),
                                        Base64.NO_WRAP,
                                    ),
                                    scannedPayloadB64 = QrPairing.encode(
                                        peer.relayPublicKeyRaw,
                                        peer.displayName,
                                        peer.bundle,
                                    ),
                                    verifiedAt = System.currentTimeMillis(),
                                    verificationMethod = VerificationMethod.VOICE,
                                )
                                contactStore.save(saved)
                                verifiedContact = saved
                                voicePeer = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("It matches — confirm & save") }
                    }
                }
            }
        }
    }
    if (onBack != null) {
        net.spritzlive.securemessenger.ui.BackToMessagesBar(onBack = onBack, label = "BACK TO CONVERSATION")
    }
    }
}
