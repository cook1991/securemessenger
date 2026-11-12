package net.spritzlive.securemessenger.net.hermes

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "NtfyWakeClient"

/**
 * Long-lived subscription to a self-hosted ntfy topic's JSON stream, used purely as
 * a "something may have changed, go reconnect" signal — the ntfy payload itself is
 * never parsed for content (by design it never carries any; this class doesn't even
 * log the raw line, as a second layer of that same guarantee). Any line read off the
 * stream just triggers [onWake]; HermesConnectionService is what actually
 * reconnects/resumes the Hermes WebSocket.
 *
 * Independent reconnect-with-backoff from HermesClient's — this stream dropping
 * doesn't mean the Hermes link itself is down, and vice versa.
 *
 * Auth model matches this project's other ntfy use (vmapp's UnifiedPush
 * endpoints): there is no ntfy-side auth-file/ACL, and [authToken] is empty by
 * default — the real protection is [topic] itself being a long, CSPRNG-generated,
 * unguessable string (same entropy model as a UnifiedPush endpoint token), not a
 * human-chosen word. hermes-config.properties.example says exactly how to
 * generate it. [authToken] stays wired up as optional defense-in-depth — if an
 * ntfy auth-file/ACL is ever added server-side, set it and this sends
 * `Authorization: Bearer <authToken>` on every request — but nothing here
 * requires it, since requiring a header the server doesn't check would be
 * cosmetic, not real protection.
 */
class NtfyWakeClient(
    private val host: String = HermesConfig.NTFY_HOST,
    private val topic: String = HermesConfig.NTFY_TOPIC,
    private val authToken: String = HermesConfig.NTFY_AUTH_TOKEN,
) {
    var onWake: (() -> Unit)? = null
    var onStateChange: ((String) -> Unit)? = null

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NtfyWakeClient-scheduler").apply { isDaemon = true }
    }
    private val backoff = ReconnectBackoff(initialMs = 2_000L, maxMs = 120_000L, stableMs = 30_000L)
    private var call: Call? = null
    private var reconnectTask: ScheduledFuture<*>? = null
    private var closed = false

    fun start() {
        closed = false
        reconnectTask?.cancel(false)
        openStream()
    }

    fun stop() {
        closed = true
        reconnectTask?.cancel(false)
        call?.cancel()
    }

    private fun openStream() {
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        val requestBuilder = Request.Builder().url("https://$host/$topic/json")
        // Optional defense-in-depth — see class doc for why this isn't required.
        if (authToken.isNotBlank()) requestBuilder.header("Authorization", "Bearer $authToken")
        val thisCall = client.newCall(requestBuilder.build())
        call = thisCall
        thisCall.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (closed) return
                report("ntfy stream failed: ${e.javaClass.simpleName}")
                scheduleReconnect()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        report("ntfy stream http ${resp.code}")
                        scheduleReconnect()
                        return
                    }
                    report("ntfy stream open")
                    val source = resp.body?.source()
                    if (source == null) {
                        scheduleReconnect()
                        return
                    }
                    val stableTask = scheduler.schedule({ backoff.reset() }, backoff.stableThresholdMs(), TimeUnit.MILLISECONDS)
                    try {
                        while (!closed) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            onWake?.invoke()
                        }
                    } catch (e: IOException) {
                        if (!closed) report("ntfy stream read failed: ${e.javaClass.simpleName}")
                    } finally {
                        stableTask.cancel(false)
                    }
                    if (!closed) scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (closed) return
        reconnectTask?.cancel(false)
        reconnectTask = scheduler.schedule({ openStream() }, backoff.nextDelayMs(), TimeUnit.MILLISECONDS)
    }

    private fun report(text: String) {
        Log.i(TAG, text)
        onStateChange?.invoke(text)
    }
}
