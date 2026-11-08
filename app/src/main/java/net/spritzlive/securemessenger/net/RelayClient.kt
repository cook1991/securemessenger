package net.spritzlive.securemessenger.net

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.spritzlive.securemessenger.crypto.RelayAuthKeyStore
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val TAG = "RelayClient"
private const val INITIAL_RECONNECT_DELAY_MS = 2_000L
private const val MAX_RECONNECT_DELAY_MS = 60_000L

/**
 * The `hello` -> `register` -> `registered` handshake, plus live/queued blob
 * send+receive. Candidate-exchange (direct P2P signaling) isn't wired yet — relay-blob
 * is the only transport path so far, which matches the plan's Phase 2 priority
 * (guaranteed relay delivery solid before optimizing for direct P2P). Full
 * NetworkRepository/RelayConnectionService wiring comes later.
 *
 * The relay's cert is deliberately self-signed (connect-by-IP, no public CA, no
 * Certificate Transparency exposure — see the plan), so the default platform trust
 * manager would reject it outright before CertificatePinner even runs. [trustedCertPem]
 * is the one cert this client trusts as its sole anchor; the SPKI pin on top of that
 * is defense-in-depth against a future swap of that anchor file.
 */
class RelayClient(
    private val host: String,
    private val port: Int,
    private val pinSha256Base64: String,
    private val trustedCertPem: String,
    private val auth: RelayAuthKeyStore,
) {
    private var webSocket: WebSocket? = null
    var onStateChange: ((String) -> Unit)? = null

    // Observed live (2026-10-31): the ws connection can die silently (idle NAT
    // timeout, network handoff) with no automatic recovery — the app sat
    // disconnected until manually relaunched, during which a peer's message to
    // it was neither delivered nor queued (relay-side gap fixed separately).
    // This reconnects with capped exponential backoff on any non-deliberate
    // close/failure, so the socket comes back on its own instead of requiring
    // a relaunch.
    private val reconnectScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RelayClient-reconnect").apply { isDaemon = true }
    }
    private var reconnectTask: ScheduledFuture<*>? = null
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    private var intentionalClose = false

    // Set the moment the server explicitly rejects a register/redeem-invite attempt
    // with "not authorized" -- distinct from intentionalClose (user asked to
    // disconnect) and from a plain network blip (where retrying makes sense). Without
    // this, a rejected connection kept retrying the same doomed register forever: the
    // backoff timer would silently open a new socket with the stale, token-less
    // register call behind the UI's back, racing whatever the user just tried after
    // entering an invite code. Cleared at the top of connect() so an explicit retry
    // (with or without a token) always gets a fresh chance.
    private var authRejected = false
    private var pendingInviteToken: String? = null

    /** Phase 3 groundwork: fires for both live `relay-blob` forwards and queued
     * `deliver` flushes — same fields, same handling either way. */
    var onBlob: ((from: String, blobId: String, ciphertext: ByteArray) -> Unit)? = null

    /** Fires once this device's `register` has been accepted — the earliest safe
     * point to send anything, since the socket exists but isn't usable before this. */
    var onRegistered: (() -> Unit)? = null

    /** Fires when the relay explicitly rejects this device — unrecognized peerId, bad
     * signature, or an invalid invite token (the relay deliberately never says which).
     * Stops the automatic reconnect loop until the next explicit connect() call, so a
     * caller can surface "you need an invite code" instead of silently spinning. */
    var onAuthRejected: (() -> Unit)? = null

    /** A peer's receipt of something this device sent — see sendDeliveryAck's doc
     * for what `level` means. */
    var onDeliveryAck: ((from: String, blobId: String, level: String) -> Unit)? = null

    /** Remote (voice-code) pairing only — see VoiceCodePairing. */
    var onBundle: ((peerId: String, bundleB64: String) -> Unit)? = null
    var onBundleNotFound: ((peerId: String) -> Unit)? = null

    fun publishBundle(bundleB64: String) {
        val message = buildJsonObject {
            put("type", "publish-bundle")
            put("bundleB64", bundleB64)
        }
        webSocket?.send(message.toString())
    }

    fun fetchBundle(peerId: String) {
        val message = buildJsonObject {
            put("type", "fetch-bundle")
            put("peerId", peerId)
        }
        webSocket?.send(message.toString())
    }

    /** Returns the blobId it generated, so the caller can correlate a later
     * delivery-ack back to the local row this blob was sent for. */
    fun sendBlob(to: String, ciphertext: ByteArray): String {
        val blobId = java.util.UUID.randomUUID().toString()
        val message = buildJsonObject {
            put("type", "relay-blob")
            put("to", to)
            put("blobId", blobId)
            put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        webSocket?.send(message.toString())
        return blobId
    }

    /** `level`: "transport" (socket delivered it — sent immediately on receipt,
     * before decryption even starts), "app" (this device decrypted it), or "read"
     * (a human actually opened the thread — see NetworkRepository.markThreadRead). */
    fun sendDeliveryAck(to: String, blobId: String, level: String) {
        val message = buildJsonObject {
            put("type", "delivery-ack")
            put("to", to)
            put("blobId", blobId)
            put("level", level)
        }
        webSocket?.send(message.toString())
    }

    /** [inviteToken], when present, is sent as a one-shot `redeem-invite` instead of
     * the normal `register` for this connection attempt — see VoiceCodePairing. */
    fun connect(inviteToken: String? = null) {
        intentionalClose = false
        authRejected = false
        pendingInviteToken = inviteToken
        reconnectTask?.cancel(false)
        val trustManager = buildTrustManager(trustedCertPem)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        val pinner = CertificatePinner.Builder()
            .add(host, "sha256/$pinSha256Base64")
            .build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .certificatePinner(pinner)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url("wss://$host:$port/").build()
        webSocket = client.newWebSocket(request, listener)
    }

    private fun buildTrustManager(certPem: String): X509TrustManager {
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certPem.byteInputStream())
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("relay", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun close() {
        intentionalClose = true
        reconnectTask?.cancel(false)
        webSocket?.close(1000, "client closing")
    }

    private fun scheduleReconnect() {
        if (intentionalClose || authRejected) return
        reconnectTask?.cancel(false)
        reconnectTask = reconnectScheduler.schedule(
            { connect() },
            reconnectDelayMs,
            TimeUnit.MILLISECONDS,
        )
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            report("ws open")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = Json.parseToJsonElement(text).jsonObject
            // Log-only, not forwarded to onStateChange: onStateChange feeds a short
            // connection-status indicator in the UI, not a protocol dump. Logs just
            // the frame type, not the raw text — a relay-blob/deliver frame's full
            // text carries the sender's peerId and ciphertext, which don't belong in
            // on-device logcat even though the relay can't read either.
            Log.i(TAG, "recv: ${msg["type"]?.jsonPrimitive?.content}")
            when (msg["type"]?.jsonPrimitive?.content) {
                "hello" -> {
                    val nonce = msg["nonce"]!!.jsonPrimitive.content
                    val nonceBytes = Base64.decode(nonce, Base64.NO_WRAP)
                    val signature = Base64.encodeToString(auth.sign(nonceBytes), Base64.NO_WRAP)
                    val publicKeyB64 = Base64.encodeToString(auth.publicKeyRaw, Base64.NO_WRAP)
                    val token = pendingInviteToken
                    val outgoing = buildJsonObject {
                        put("type", if (token != null) "redeem-invite" else "register")
                        put("publicKey", publicKeyB64)
                        put("nonce", nonce)
                        put("signature", signature)
                        if (token != null) put("token", token)
                    }
                    webSocket.send(outgoing.toString())
                }
                "registered" -> {
                    report("REGISTERED as ${auth.peerId}")
                    onRegistered?.invoke()
                }
                "relay-blob", "deliver" -> {
                    val from = msg["from"]!!.jsonPrimitive.content
                    val blobId = msg["blobId"]!!.jsonPrimitive.content
                    // Fired immediately on receipt, independent of decrypt success —
                    // "transport" means the socket got it, nothing more.
                    sendDeliveryAck(from, blobId, "transport")
                    val ciphertext = Base64.decode(msg["ciphertext"]!!.jsonPrimitive.content, Base64.NO_WRAP)
                    onBlob?.invoke(from, blobId, ciphertext)
                }
                "delivery-ack" -> {
                    val from = msg["from"]!!.jsonPrimitive.content
                    val blobId = msg["blobId"]!!.jsonPrimitive.content
                    val level = msg["level"]!!.jsonPrimitive.content
                    onDeliveryAck?.invoke(from, blobId, level)
                }
                "bundle" -> {
                    val peerId = msg["peerId"]!!.jsonPrimitive.content
                    val bundleB64 = msg["bundleB64"]!!.jsonPrimitive.content
                    onBundle?.invoke(peerId, bundleB64)
                }
                "bundle-not-found" -> onBundleNotFound?.invoke(msg["peerId"]!!.jsonPrimitive.content)
                "error" -> {
                    val message = msg["message"]?.jsonPrimitive?.content
                    report("SERVER ERROR: $message")
                    if (message == "not authorized") {
                        authRejected = true
                        onAuthRejected?.invoke()
                    }
                }
                else -> report("unhandled message type")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            report("ws failure: ${t.javaClass.simpleName}: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            report("ws closed: $code $reason")
            scheduleReconnect()
        }
    }

    private fun report(text: String) {
        Log.i(TAG, text)
        onStateChange?.invoke(text)
    }
}
