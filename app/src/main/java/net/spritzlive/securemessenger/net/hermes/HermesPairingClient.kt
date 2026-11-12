package net.spritzlive.securemessenger.net.hermes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
private data class PairRequest(val code: String)

@Serializable
private data class PairResponse(
    val token: String,
    @SerialName("ntfy_host") val ntfyHost: String? = null,
    @SerialName("ntfy_topic") val ntfyTopic: String? = null,
    @SerialName("ntfy_auth_token") val ntfyAuthToken: String? = null,
)

// The gateway's success body carries fields beyond these (e.g. device_id).
private val json = Json { ignoreUnknownKeys = true }

/** Shown in the UI instead of exception text: kotlinx.serialization messages
 * embed the raw response body, which on a 2xx is the device token itself. */
class HermesPairingException(message: String) : Exception(message)

class HermesPairing(val token: ByteArray, val config: HermesGatewayConfig)

/**
 * One-shot HTTPS exchange of a setup code's pairing code for a permanent device
 * token. Pinned to the setup code's pins on top of normal CA validation (no custom
 * trust manager): this request hands back the long-lived credential and the ntfy
 * topic secret, so it gets the same pinning as the WebSocket.
 */
class HermesPairingClient {
    /** On success returns the raw token bytes and the full gateway config, with the
     * gateway's ntfy wake settings filled in when it provided valid ones. Never logs
     * the code, token or topic — callers hand the result straight to HermesAuthStore. */
    fun exchange(setup: HermesSetupCode.Parsed): Result<HermesPairing> = runCatching {
        val gateway = setup.config
        val pinner = CertificatePinner.Builder().apply {
            gateway.pins.forEach { add(gateway.host, "sha256/$it") }
        }.build()
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .certificatePinner(pinner)
            .build()
        val body = Json.encodeToString(PairRequest.serializer(), PairRequest(setup.pairingCode))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(gateway.pairUrl()).post(body).build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 403 -> throw HermesPairingException("Code is invalid or expired")
                !response.isSuccessful -> throw HermesPairingException("Pairing failed (HTTP ${response.code})")
            }
            val responseBody = response.body?.string()
                ?: throw HermesPairingException("Pairing failed: empty response")
            val parsed = runCatching { json.decodeFromString(PairResponse.serializer(), responseBody) }
                .getOrElse { throw HermesPairingException("Pairing failed: unexpected response format") }
            val token = runCatching { HermesTokenCodec.decodeHex(parsed.token) }
                .getOrElse { throw HermesPairingException("Pairing failed: token is not valid hex") }
            // The code is already spent by now, so bad wake settings don't fail the
            // pairing: messages still work, just without ntfy wake-ups.
            val withWake = gateway.copy(ntfyHost = parsed.ntfyHost, ntfyTopic = parsed.ntfyTopic, ntfyAuthToken = parsed.ntfyAuthToken)
            val config = runCatching { withWake.validate() }.getOrDefault(gateway)
            HermesPairing(token, config)
        }
    }
}
