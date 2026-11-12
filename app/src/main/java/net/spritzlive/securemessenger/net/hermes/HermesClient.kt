package net.spritzlive.securemessenger.net.hermes

import android.util.Base64
import android.util.Log
import kotlinx.serialization.json.Json
import net.spritzlive.securemessenger.crypto.HermesAuthStore
import net.spritzlive.securemessenger.crypto.HermesHmac
import net.spritzlive.securemessenger.crypto.wipe
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "HermesClient"
private const val PING_INTERVAL_MS = 30_000L
private const val PONG_TIMEOUT_MS = 10_000L

/** Close code the gateway is assumed to use for "your device token isn't valid" —
 * RFC 6455 reserves 4000-4999 for private use; this is this project's own
 * convention and needs confirming against the real Hermes gateway once it exists. */
private const val CLOSE_CODE_UNAUTHORIZED = 4001

/**
 * WSS client for the Hermes relay link. Same OkHttp WebSocket + hand-rolled
 * reconnect-with-backoff shape as RelayClient, but a different protocol: plain
 * device-token auth (no challenge/response handshake) plus a per-message HMAC
 * instead of E2E ciphertext, since the Hermes gateway is the trusted counterparty
 * here rather than a blind relay.
 *
 * Deliberately uses the platform default trust store — no custom SSLContext, no
 * CertificatePinner, no TrustManager override of any kind. The gateway is expected
 * to hold a publicly-trusted certificate; "no certificate trust overrides, ever"
 * is satisfied by not writing any trust-override code at all, rather than by
 * writing one and being careful with it.
 */
class HermesClient(
    private val auth: HermesAuthStore,
    private val host: String = HermesConfig.HOST,
    private val port: Int = HermesConfig.PORT,
    private val wsPath: String = HermesConfig.WS_PATH,
    private val pinSha256Base64: String = HermesConfig.PIN_SHA256_B64,
) {
    private var webSocket: WebSocket? = null
    private var signingKey: ByteArray? = null

    var onStateChange: ((String) -> Unit)? = null

    /** Fires once an inbound `message` frame's HMAC has verified — never fires for
     * a frame that failed verification (see HermesSecurityLog for that path). */
    var onMessage: ((id: String, ts: Long, text: String) -> Unit)? = null

    /** Fires when the peer acks a message this device sent, by that message's id. */
    var onAck: ((id: String) -> Unit)? = null

    /** Fires when the gateway closes the connection over an invalid/expired device
     * token. Stops automatic reconnection until the next explicit connect(). */
    var onAuthRejected: (() -> Unit)? = null

    /** Fires when connect() is called with no device token stored yet — the caller
     * should route the person to the pairing flow. */
    var onNeedsPairing: (() -> Unit)? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HermesClient-scheduler").apply { isDaemon = true }
    }
    private val backoff = ReconnectBackoff()
    private var reconnectTask: ScheduledFuture<*>? = null
    private var pingTask: ScheduledFuture<*>? = null
    private var pongTimeoutTask: ScheduledFuture<*>? = null
    private var stableResetTask: ScheduledFuture<*>? = null
    private var intentionalClose = false
    private var authRejected = false

    fun connect() {
        intentionalClose = false
        authRejected = false
        reconnectTask?.cancel(false)

        val token = auth.loadDeviceToken()
        if (token == null) {
            report("no device token stored — pairing required")
            onNeedsPairing?.invoke()
            return
        }
        signingKey = HermesHmac.deriveSigningKey(token)
        // The per-message HMAC proves authenticity of each frame once a socket
        // exists, but says nothing about who's allowed to open the socket in the
        // first place — the gateway needs the token itself, on the handshake,
        // to identify this device before any frame has been exchanged. The stored
        // token is raw bytes; the wire form is hex (see HermesTokenCodec). OkHttp's
        // Headers require a String, so it's materialized once here, not retained.
        val authHeaderValue = "Bearer " + HermesTokenCodec.encodeHex(token)
        token.wipe()

        // No custom SSLSocketFactory/TrustManager — platform CA validation still
        // runs unmodified. CertificatePinner below only narrows what's accepted
        // further; see HermesConfig's doc for why that's additive, not a trust
        // override.
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
        buildPinner()?.let { builder.certificatePinner(it) }
        val client = builder.build()
        val request = Request.Builder()
            .url("wss://$host:$port$wsPath")
            .header("Authorization", authHeaderValue)
            .build()
        webSocket = client.newWebSocket(request, listener)
    }

    /** [pinSha256Base64] holds one or more comma-separated base64 SPKI hashes —
     * more than one so a backup pin (e.g. the issuing CA, or a pre-generated
     * replacement leaf key) can be rolled out ahead of a cert renewal without
     * ever running with only a single pin that renewal would then invalidate.
     * Null (no pinning applied) only when the config is left blank, e.g. a dev
     * build before hermes-config.properties has real values — connect() still
     * works, matching RelayClient's own placeholder-tolerant build. */
    private fun buildPinner(): CertificatePinner? {
        val pins = pinSha256Base64.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (pins.isEmpty()) return null
        val builder = CertificatePinner.Builder()
        for (pin in pins) builder.add(host, "sha256/$pin")
        return builder.build()
    }

    fun close() {
        intentionalClose = true
        cancelAllTimers()
        webSocket?.close(1000, "client closing")
        signingKey?.wipe()
        signingKey = null
    }

    /** Sends a signed message frame and returns its id, so the caller can correlate
     * a later ack back to its own locally-stored row. Returns null if there's no
     * live signing key (not connected / pairing not complete) — caller should treat
     * this the same as any other send failure (store as pending, let reconnect +
     * a future resend path, if ever added, handle it). */
    fun sendMessage(text: String): String? {
        val key = signingKey ?: return null
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        val hmacB64 = Base64.encodeToString(HermesHmac.sign(key, id, ts, text), Base64.NO_WRAP)
        val frame = HermesFrame(id = id, type = HermesFrame.TYPE_MESSAGE, ts = ts, text = text, hmac = hmacB64)
        webSocket?.send(Json.encodeToString(HermesFrame.serializer(), frame))
        return id
    }

    private fun sendAck(forId: String) {
        val frame = HermesFrame(id = forId, type = HermesFrame.TYPE_ACK, ts = System.currentTimeMillis())
        webSocket?.send(Json.encodeToString(HermesFrame.serializer(), frame))
    }

    private fun sendPing() {
        val frame = HermesFrame(id = UUID.randomUUID().toString(), type = HermesFrame.TYPE_PING, ts = System.currentTimeMillis())
        webSocket?.send(Json.encodeToString(HermesFrame.serializer(), frame))
        pongTimeoutTask?.cancel(false)
        pongTimeoutTask = scheduler.schedule({
            report("pong timeout — reconnecting")
            // WebSocket.cancel() never invokes listener callbacks (per OkHttp's own
            // contract), unlike close()/onFailure — so the reconnect has to be
            // driven explicitly from here rather than relying on onClosed/onFailure.
            webSocket?.cancel()
            scheduleReconnect()
        }, PONG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun sendPong(forId: String) {
        val frame = HermesFrame(id = forId, type = HermesFrame.TYPE_PONG, ts = System.currentTimeMillis())
        webSocket?.send(Json.encodeToString(HermesFrame.serializer(), frame))
    }

    private fun sendResumeIfNeeded() {
        val lastId = auth.loadLastId() ?: return
        val frame = HermesFrame(
            id = UUID.randomUUID().toString(),
            type = HermesFrame.TYPE_RESUME,
            ts = System.currentTimeMillis(),
            last_id = lastId,
        )
        webSocket?.send(Json.encodeToString(HermesFrame.serializer(), frame))
    }

    private fun onSocketOpened() {
        report("ws open")
        sendResumeIfNeeded()
        pingTask?.cancel(false)
        pingTask = scheduler.scheduleWithFixedDelay({ sendPing() }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        stableResetTask?.cancel(false)
        stableResetTask = scheduler.schedule({ backoff.reset() }, backoff.stableThresholdMs(), TimeUnit.MILLISECONDS)
    }

    private fun handleFrame(text: String) {
        val frame = runCatching { Json.decodeFromString(HermesFrame.serializer(), text) }
            .getOrElse {
                HermesSecurityLog.malformedFrame("unparseable frame")
                return
            }
        when (frame.type) {
            HermesFrame.TYPE_PONG -> pongTimeoutTask?.cancel(false)
            HermesFrame.TYPE_PING -> sendPong(frame.id)
            HermesFrame.TYPE_MESSAGE -> handleMessageFrame(frame)
            HermesFrame.TYPE_ACK -> onAck?.invoke(frame.id)
            else -> HermesSecurityLog.malformedFrame("unknown frame type")
        }
    }

    private fun handleMessageFrame(frame: HermesFrame) {
        val text = frame.text
        val hmacB64 = frame.hmac
        val key = signingKey
        if (text == null || hmacB64 == null || key == null) {
            HermesSecurityLog.malformedFrame("message frame missing text/hmac")
            return
        }
        val expected = runCatching { Base64.decode(hmacB64, Base64.NO_WRAP) }.getOrNull()
        if (expected == null || !HermesHmac.verify(key, frame.id, frame.ts, text, expected)) {
            HermesSecurityLog.hmacVerificationFailed(frame.id)
            return
        }
        auth.saveLastId(frame.id)
        sendAck(frame.id)
        onMessage?.invoke(frame.id, frame.ts, text)
    }

    private fun cancelAllTimers() {
        reconnectTask?.cancel(false)
        pingTask?.cancel(false)
        pongTimeoutTask?.cancel(false)
        stableResetTask?.cancel(false)
    }

    private fun scheduleReconnect() {
        pingTask?.cancel(false)
        pongTimeoutTask?.cancel(false)
        stableResetTask?.cancel(false)
        if (intentionalClose || authRejected) return
        reconnectTask?.cancel(false)
        reconnectTask = scheduler.schedule({ connect() }, backoff.nextDelayMs(), TimeUnit.MILLISECONDS)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onSocketOpened()

        // Never logs the raw frame text — a message frame's text is this device's
        // (or Hermes's) actual chat content, which doesn't belong in logcat even
        // though this link isn't E2E-encrypted on top of TLS.
        override fun onMessage(webSocket: WebSocket, text: String) = handleFrame(text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            report("ws failure: ${t.javaClass.simpleName}: ${t.message}")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            report("ws closed: $code $reason")
            if (code == CLOSE_CODE_UNAUTHORIZED) {
                authRejected = true
                HermesSecurityLog.authRejected()
                onAuthRejected?.invoke()
                return
            }
            scheduleReconnect()
        }
    }

    private fun report(text: String) {
        Log.i(TAG, text)
        onStateChange?.invoke(text)
    }
}
