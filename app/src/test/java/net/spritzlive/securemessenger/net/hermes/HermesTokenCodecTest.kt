package net.spritzlive.securemessenger.net.hermes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HermesTokenCodecTest {
    @Test
    fun `decodeHex yields raw bytes, not the UTF-8 of the hex digits`() {
        val bytes = HermesTokenCodec.decodeHex("a34e00ff")
        assertArrayEquals(byteArrayOf(0xa3.toByte(), 0x4e, 0x00, 0xff.toByte()), bytes)
    }

    @Test
    fun `a 64-char hex token decodes to 32 raw bytes`() {
        val hex = "54fd" + "0".repeat(60)
        assertEquals(32, HermesTokenCodec.decodeHex(hex).size)
    }

    @Test
    fun `encode then decode round-trips every byte value`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, HermesTokenCodec.decodeHex(HermesTokenCodec.encodeHex(all)))
    }

    @Test
    fun `decode accepts uppercase and encode emits lowercase`() {
        val bytes = HermesTokenCodec.decodeHex("ABCDEF")
        assertEquals("abcdef", HermesTokenCodec.encodeHex(bytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `odd-length hex is rejected`() {
        HermesTokenCodec.decodeHex("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-hex characters are rejected`() {
        HermesTokenCodec.decodeHex("zz")
    }
}
