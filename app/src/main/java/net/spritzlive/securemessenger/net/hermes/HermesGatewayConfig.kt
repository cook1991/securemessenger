package net.spritzlive.securemessenger.net.hermes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Where a person's own Hermes gateway lives and how to recognise it. Nothing here is
 * compiled into the app: it arrives at setup time (see [HermesSetupCode]) and is
 * stored Keystore-wrapped in HermesAuthStore, so one store build serves everyone and
 * nobody sees Hermes until they set up their own.
 *
 * Trust: the platform CA store still makes the chain-of-trust decision; [pins] add a
 * CertificatePinner check on top (the connection must BOTH validate against a real
 * CA AND match a pin), never a replacement for it. At least one pin is required.
 *
 * The ntfy fields are optional (a gateway without ntfy just has no wake signal) and
 * never come from the setup code: the gateway returns them from /pair, over the
 * already-pinned connection, so the topic secret never appears in a QR/screenshot.
 */
@Serializable
data class HermesGatewayConfig(
    val host: String,
    val port: Int,
    @SerialName("ws_path") val wsPath: String,
    @SerialName("pair_path") val pairPath: String,
    val pins: List<String>,
    @SerialName("ntfy_host") val ntfyHost: String? = null,
    @SerialName("ntfy_topic") val ntfyTopic: String? = null,
    @SerialName("ntfy_auth_token") val ntfyAuthToken: String? = null,
) {
    fun wsUrl(): String = "wss://$host:$port$wsPath"
    fun pairUrl(): String = "https://$host:$port$pairPath"
    val hasWake: Boolean get() = !ntfyHost.isNullOrBlank() && !ntfyTopic.isNullOrBlank()

    /** Throws [IllegalArgumentException] with a message safe to show (no secrets). */
    fun validate(): HermesGatewayConfig {
        require(HOSTNAME.matches(host)) { "gateway address is not a valid host name" }
        require(port in 1..65535) { "gateway port is out of range" }
        require(isSafePath(wsPath)) { "gateway connection path is invalid" }
        require(isSafePath(pairPath)) { "gateway pairing path is invalid" }
        require(pins.size in 1..10) { "the code must include 1 to 10 certificate pins" }
        require(pins.all(::isValidPin)) { "a certificate pin is malformed" }
        if (ntfyHost != null || ntfyTopic != null) {
            require(ntfyHost != null && HOSTNAME.matches(ntfyHost)) { "wake-up server address is invalid" }
            require(ntfyTopic != null && NTFY_TOPIC.matches(ntfyTopic)) { "wake-up topic is invalid" }
        }
        ntfyAuthToken?.let { require(PRINTABLE_TOKEN.matches(it)) { "wake-up auth token is invalid" } }
        return this
    }

    companion object {
        private val HOSTNAME = Regex("^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$")
        private val PATH = Regex("^/[A-Za-z0-9._~/-]{0,200}$")

        // ntfy's own topic alphabet; 20+ chars because the topic is the secret.
        private val NTFY_TOPIC = Regex("^[A-Za-z0-9_-]{20,64}$")
        private val PRINTABLE_TOKEN = Regex("^[\\x21-\\x7E]{1,256}$")

        private fun isSafePath(p: String) = PATH.matches(p) && !p.contains("..") && !p.contains("//")

        private fun isValidPin(pin: String): Boolean =
            runCatching { Base64.getDecoder().decode(pin).size == 32 }.getOrDefault(false)
    }
}

/**
 * The one-time setup code a gateway shows its owner, as a QR or text to paste:
 * `hermes-setup:1:` followed by unpadded base64url of
 * `{"host","port","ws_path","pair_path","pins":[...],"code"}`.
 * It carries the gateway's location and pins plus the single-use pairing code;
 * spending the code (HermesPairingClient) is what turns it into a device token.
 */
object HermesSetupCode {
    const val PREFIX = "hermes-setup:1:"

    @Serializable
    private data class Payload(
        val host: String,
        val port: Int = 443,
        @SerialName("ws_path") val wsPath: String = "/ws",
        @SerialName("pair_path") val pairPath: String = "/pair",
        val pins: List<String>,
        val code: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val CODE = Regex("^[\\x21-\\x7E]{1,256}$")

    data class Parsed(val config: HermesGatewayConfig, val pairingCode: String)

    /** Throws [IllegalArgumentException] with a message safe to show. */
    fun parse(text: String): Parsed {
        val trimmed = text.trim()
        require(trimmed.startsWith(PREFIX)) { "That isn't a Hermes setup code" }
        val bytes = runCatching { Base64.getUrlDecoder().decode(trimmed.removePrefix(PREFIX)) }
            .getOrElse { throw IllegalArgumentException("The setup code is damaged or incomplete") }
        val payload = runCatching { json.decodeFromString(Payload.serializer(), String(bytes, Charsets.UTF_8)) }
            .getOrElse { throw IllegalArgumentException("The setup code is damaged or incomplete") }
        require(CODE.matches(payload.code)) { "The setup code's pairing code is invalid" }
        val config = HermesGatewayConfig(payload.host, payload.port, payload.wsPath, payload.pairPath, payload.pins)
        return Parsed(config.validate(), payload.code)
    }

    /** For tests and gateway-side reference: the exact encoding [parse] accepts. */
    fun encode(host: String, port: Int, wsPath: String, pairPath: String, pins: List<String>, code: String): String {
        val payload = Payload(host, port, wsPath, pairPath, pins, code)
        val body = Json.encodeToString(Payload.serializer(), payload).toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body)
    }
}
