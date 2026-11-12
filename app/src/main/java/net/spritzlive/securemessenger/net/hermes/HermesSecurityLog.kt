package net.spritzlive.securemessenger.net.hermes

import android.util.Log

private const val TAG = "HermesSecurityLog"

/**
 * Local-only (logcat) record of security-relevant events on the Hermes link —
 * HMAC failures, auth rejection, malformed frames. Never ships anywhere off-device
 * (no analytics/crash-reporter hook here), and never includes the token, the derived
 * HMAC key, or message text/ciphertext — only the frame id and a reason string, which
 * is enough to debug "why did this get dropped" without logging the thing that was
 * dropped.
 */
object HermesSecurityLog {
    fun hmacVerificationFailed(frameId: String) {
        Log.w(TAG, "dropped inbound frame $frameId: HMAC verification failed")
    }

    fun malformedFrame(reason: String) {
        Log.w(TAG, "dropped inbound frame: $reason")
    }

    fun authRejected() {
        Log.w(TAG, "Hermes gateway rejected this device's token")
    }
}
