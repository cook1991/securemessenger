package net.spritzlive.securemessenger.net.hermes

/**
 * The device token is raw bytes everywhere inside the app (HermesAuthStore, HKDF
 * input); hex only exists at the wire boundary (the pairing response's JSON field
 * and the Bearer header). Both directions live here so the two boundaries can't
 * drift apart. Pure JVM, unit-tested.
 */
object HermesTokenCodec {
    fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "token hex has odd length" }
        return ByteArray(hex.length / 2) { i ->
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "token is not valid hex" }
            ((hi shl 4) or lo).toByte()
        }
    }

    fun encodeHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(Character.forDigit(v ushr 4, 16)).append(Character.forDigit(v and 0x0F, 16))
        }
        return out.toString()
    }
}
