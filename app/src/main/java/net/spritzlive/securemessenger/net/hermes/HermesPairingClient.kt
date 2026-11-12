package net.spritzlive.securemessenger.net.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
private data class PairRequest(val code: String)

@Serializable
private data class PairResponse(val token: String)

/**
 * One-shot HTTPS exchange of a pairing code (shown to the person by the gateway
 * operator, entered once) for a permanent device token. Plain OkHttp over HTTPS —
 * default platform trust store, no custom SSLContext, no pinning: same "no
 * certificate trust overrides" rule as HermesClient's WebSocket, and this is the
 * one request where a compromised/incorrect cert would hand an attacker the actual
 * long-lived credential, so there's no case for ever relaxing it here.
 */
class HermesPairingClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Returns the raw device token bytes on success. Never logs [code] or the
     * resulting token — callers are responsible for handing the token straight to
     * HermesAuthStore rather than holding onto it. */
    fun exchangeCodeForToken(code: String): Result<ByteArray> = runCatching {
        val body = Json.encodeToString(PairRequest.serializer(), PairRequest(code))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(HermesConfig.PAIR_URL)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("pairing exchange failed: HTTP ${response.code}")
            }
            val responseBody = response.body?.string() ?: error("empty pairing response")
            val parsed = Json.decodeFromString(PairResponse.serializer(), responseBody)
            HermesTokenCodec.decodeHex(parsed.token)
        }
    }
}
