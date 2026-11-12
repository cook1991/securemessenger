package net.spritzlive.securemessenger.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HKDF_CONTEXT = "hermes-relay-hmac-v1"
private const val HMAC_KEY_LEN_BYTES = 32

/**
 * Per-message authentication for the Hermes relay link, independent of the Signal
 * session used for the app's primary contact — this is plain HMAC-SHA256 over the
 * frame, not E2E ciphertext, since the Hermes gateway itself is the trusted
 * counterparty (unlike the relay, which only ever forwards opaque blobs it can't
 * read). Deliberately pure JVM (no Android types) so it's unit-testable without
 * Robolectric.
 */
object HermesHmac {
    /** [deviceToken] is the permanent token from pairing as raw bytes (hex-decoded
     * from the wire by HermesTokenCodec, never the UTF-8 of its hex digits) — the
     * HKDF input keying material, same bytes the gateway derives from. No salt (HKDF's defined behavior for a
     * missing salt: treated as a zero-filled block), since the token itself is
     * already a high-entropy secret pulled from the gateway's own handshake. */
    fun deriveSigningKey(deviceToken: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(deviceToken, null, HKDF_CONTEXT.toByteArray(Charsets.UTF_8)))
        val out = ByteArray(HMAC_KEY_LEN_BYTES)
        hkdf.generateBytes(out, 0, out.size)
        return out
    }

    /** Canonical signed bytes: id, then decimal ts, then text, each UTF-8, concatenated
     * with no delimiter — fixed-format fields (uuid, decimal long) make this
     * unambiguous without needing one. */
    private fun canonicalBytes(id: String, ts: Long, text: String): ByteArray =
        id.toByteArray(Charsets.UTF_8) + ts.toString().toByteArray(Charsets.UTF_8) + text.toByteArray(Charsets.UTF_8)

    fun sign(signingKey: ByteArray, id: String, ts: Long, text: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        return mac.doFinal(canonicalBytes(id, ts, text))
    }

    /** Constant-time comparison (MessageDigest.isEqual) — a timing-leaky equals()
     * on a MAC check would defeat the point of having one. */
    fun verify(signingKey: ByteArray, id: String, ts: Long, text: String, expectedHmac: ByteArray): Boolean =
        MessageDigest.isEqual(sign(signingKey, id, ts, text), expectedHmac)
}
