package net.spritzlive.securemessenger.pairing

import android.graphics.Bitmap
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.security.MessageDigest

/**
 * In-person pairing: one QR scan carries BOTH the relay-auth public key and the full
 * Signal PreKeyBundle in one shot — physical proximity at the moment of the camera
 * scan is itself the authentication channel (an attacker would need to visually
 * intercept/spoof the code), so unlike remote/voice-code pairing (not yet built;
 * needs a relay-side lookup since a 64-hex-char peerId can't be read aloud), no
 * extra verification round-trip is required. The safety-number fingerprint is still
 * computed and shown after a scan, matching Signal's own UX convention, but here
 * it's a confirmation display, not a required separate check.
 *
 * Wire format is the same length-prefixed block packing used throughout this
 * codebase's other local stores (RelayAuthKeyStore, SignalIdentityStore):
 * [relayPubKey(32) | displayName(utf8) | registrationId(4) | deviceId(4) |
 * preKeyId(4) | preKeyPublic(33) | signedPreKeyId(4) | signedPreKeyPublic(33) |
 * signedPreKeySignature(64) | identityKey(33) | kyberPreKeyId(4) |
 * kyberPreKeyPublic(~1568) | kyberPreKeySignature(64)] — ~1.9KB raw, dominated by
 * the Kyber-1024 public key.
 *
 * The packed bytes are base64'd wherever this needs to be a String (persistence in
 * ContactVerificationStore, [decode]'s input) — but NOT for the QR bitmap itself:
 * base64's 4/3 inflation pushed a ~1.9KB payload to ~2540 chars, which overflowed
 * even QR version 40 at error-correction level M (crashed with zxing's own
 * `WriterException: Data too big`, confirmed on real hardware). [generateQrBitmap]
 * instead feeds the raw packed bytes straight into zxing's byte-mode QR encoding via
 * the standard ISO-8859-1 round-trip trick (every byte 0-255 maps 1:1 to a Java
 * char of the same value and back, losslessly — unlike UTF-8, which isn't 1:1 above
 * 127), keeping the actual QR payload at ~1.9KB with real margin under the ~2.3KB
 * cap at M.
 *
 * The scan side reads `ScanIntentResult.contents` (the decoded TEXT), not `rawBytes`
 * — confirmed on real hardware that `rawBytes` is NOT a clean extraction of this
 * payload. It's `DecoderResult.getRawBytes()` from zxing-core, which turned out (by
 * reading zxing's own source after the corrupted-decode failures below didn't make
 * sense as camera noise) to be the QR's entire raw codeword bitstream — mode
 * indicator, length field, and ECI segment framing all still byte-aligned in front
 * of the actual payload — not a re-extraction of just the byte-mode segment's data.
 * `contents`, built from `DecodedBitStreamParser`'s per-segment `new String(readBytes,
 * encoding)`, IS the clean extraction; converting it back via the same ISO-8859-1
 * charset recovers the exact original bytes.
 */
object QrPairing {
    data class ScannedPeer(
        val relayPeerId: String,
        val relayPublicKeyRaw: ByteArray,
        val displayName: String,
        val bundle: PreKeyBundle,
    )

    // Both sides are always this app's own build, so this only needs to be internally
    // consistent, not negotiated — hardcoded identically on both ends.
    private const val FINGERPRINT_VERSION = 1

    // >112 bits of security per NumericFingerprintGenerator's own documented guidance.
    private const val FINGERPRINT_ITERATIONS = 5200

    private fun packedBytes(relayPublicKeyRaw: ByteArray, displayName: String, bundle: PreKeyBundle): ByteArray =
        packBlocks(
            relayPublicKeyRaw,
            displayName.toByteArray(Charsets.UTF_8),
            intToBytes(bundle.registrationId),
            intToBytes(bundle.deviceId),
            intToBytes(bundle.preKeyId),
            bundle.preKey!!.serialize(),
            intToBytes(bundle.signedPreKeyId),
            bundle.signedPreKey.serialize(),
            bundle.signedPreKeySignature,
            bundle.identityKey.serialize(),
            intToBytes(bundle.kyberPreKeyId),
            bundle.kyberPreKey.serialize(),
            bundle.kyberPreKeySignature,
        )

    fun encode(relayPublicKeyRaw: ByteArray, displayName: String, bundle: PreKeyBundle): String =
        Base64.encodeToString(packedBytes(relayPublicKeyRaw, displayName, bundle), Base64.NO_WRAP)

    fun decode(content: String): ScannedPeer = decodeFromRawBytes(Base64.decode(content, Base64.NO_WRAP))

    /** For a freshly-scanned QR's `ScanIntentResult.contents` — see the class doc. */
    fun decodeFromQrText(text: String): ScannedPeer = decodeFromRawBytes(text.toByteArray(Charsets.ISO_8859_1))

    private fun decodeFromRawBytes(bytes: ByteArray): ScannedPeer {
        val reader = BlockReader(bytes)
        val relayPublicKeyRaw = reader.next()
        val displayName = String(reader.next(), Charsets.UTF_8)
        val registrationId = bytesToInt(reader.next())
        val deviceId = bytesToInt(reader.next())
        val preKeyId = bytesToInt(reader.next())
        val preKeyPublic = ECPublicKey(reader.next())
        val signedPreKeyId = bytesToInt(reader.next())
        val signedPreKeyPublic = ECPublicKey(reader.next())
        val signedPreKeySignature = reader.next()
        val identityKey = IdentityKey(reader.next())
        val kyberPreKeyId = bytesToInt(reader.next())
        val kyberPreKeyPublic = KEMPublicKey(reader.next())
        val kyberPreKeySignature = reader.next()

        val bundle = PreKeyBundle(
            registrationId,
            deviceId,
            preKeyId,
            preKeyPublic,
            signedPreKeyId,
            signedPreKeyPublic,
            signedPreKeySignature,
            identityKey,
            kyberPreKeyId,
            kyberPreKeyPublic,
            kyberPreKeySignature,
        )
        return ScannedPeer(sha256Hex(relayPublicKeyRaw), relayPublicKeyRaw, displayName, bundle)
    }

    fun generateQrBitmap(
        relayPublicKeyRaw: ByteArray,
        displayName: String,
        bundle: PreKeyBundle,
        sizePx: Int = 900,
    ): Bitmap {
        val packed = packedBytes(relayPublicKeyRaw, displayName, bundle)
        // ISO-8859-1 maps bytes 0-255 to chars 0-255 1:1, so this string carries the
        // raw bytes losslessly through zxing's String-based byte-mode encoder — see
        // the class doc for why this is needed instead of base64 text.
        val rawByteString = String(packed, Charsets.ISO_8859_1)
        // M, not L: confirmed on real hardware that L-level redundancy (~7%) wasn't
        // enough margin against real-world camera scan noise — a scan "succeeded" (no
        // FormatException) but decoded to corrupted bytes (BlockReader read a garbage
        // length prefix off a few flipped bits Reed-Solomon's thin L budget didn't
        // catch). M is also the ceiling here: at ~1.9KB this payload already exceeds
        // Q's entire V40 byte-mode capacity (1663B), so M (2331B cap) is the most
        // redundancy this size can get, not a convenience tradeoff. The display-size
        // fix (filling the screen width) already addressed module-size legibility
        // separately, so this isn't fighting that fix — it's a different failure mode.
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to "M",
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        )
        return BarcodeEncoder().encodeBitmap(rawByteString, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }

    /** Both devices compute this independently and should see identical digit text. */
    fun fingerprintDisplayText(
        localPeerId: String,
        localIdentityKey: IdentityKey,
        remotePeerId: String,
        remoteIdentityKey: IdentityKey,
    ): String {
        val fingerprint = NumericFingerprintGenerator(FINGERPRINT_ITERATIONS).createFor(
            FINGERPRINT_VERSION,
            localPeerId.toByteArray(Charsets.UTF_8),
            localIdentityKey,
            remotePeerId.toByteArray(Charsets.UTF_8),
            remoteIdentityKey,
        )
        return fingerprint.displayableFingerprint.displayText
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun packBlocks(vararg blocks: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        for (block in blocks) {
            val len = block.size
            out.add((len ushr 24).toByte())
            out.add((len ushr 16).toByte())
            out.add((len ushr 8).toByte())
            out.add(len.toByte())
            block.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private class BlockReader(private val bytes: ByteArray) {
        private var offset = 0
        fun next(): ByteArray {
            val len = ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
            offset += 4
            // A corrupted/misread scan can produce a garbage length here — surface it
            // plainly as "the scan was corrupted" rather than a cryptic range crash
            // (confirmed on real hardware: a marginal scan decoded without error but
            // with a few flipped bits the QR's own error correction didn't catch).
            require(len in 0..(bytes.size - offset)) {
                "corrupt block length $len at offset ${offset - 4} (payload size ${bytes.size})"
            }
            val block = bytes.copyOfRange(offset, offset + len)
            offset += len
            return block
        }
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun bytesToInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
}
