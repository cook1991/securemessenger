package net.spritzlive.securemessenger.crypto

import net.spritzlive.securemessenger.net.hermes.HermesTokenCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesHmacTest {
    private val token = "a-permanent-device-token".toByteArray(Charsets.UTF_8)

    @Test
    fun `verify accepts a signature produced by sign with the same inputs`() {
        val key = HermesHmac.deriveSigningKey(token)
        val signature = HermesHmac.sign(key, "id-1", 1_700_000_000_000L, "hello hermes")
        assertTrue(HermesHmac.verify(key, "id-1", 1_700_000_000_000L, "hello hermes", signature))
    }

    @Test
    fun `verify rejects a signature if the text changed`() {
        val key = HermesHmac.deriveSigningKey(token)
        val signature = HermesHmac.sign(key, "id-1", 1_700_000_000_000L, "hello hermes")
        assertFalse(HermesHmac.verify(key, "id-1", 1_700_000_000_000L, "tampered text", signature))
    }

    @Test
    fun `verify rejects a signature if the id changed`() {
        val key = HermesHmac.deriveSigningKey(token)
        val signature = HermesHmac.sign(key, "id-1", 1_700_000_000_000L, "hello hermes")
        assertFalse(HermesHmac.verify(key, "id-2", 1_700_000_000_000L, "hello hermes", signature))
    }

    @Test
    fun `verify rejects a signature if the timestamp changed`() {
        val key = HermesHmac.deriveSigningKey(token)
        val signature = HermesHmac.sign(key, "id-1", 1_700_000_000_000L, "hello hermes")
        assertFalse(HermesHmac.verify(key, "id-1", 1_700_000_000_001L, "hello hermes", signature))
    }

    @Test
    fun `verify rejects a signature produced under a different device token`() {
        val key = HermesHmac.deriveSigningKey(token)
        val otherKey = HermesHmac.deriveSigningKey("a-different-device-token".toByteArray(Charsets.UTF_8))
        val signature = HermesHmac.sign(key, "id-1", 1_700_000_000_000L, "hello hermes")
        assertFalse(HermesHmac.verify(otherKey, "id-1", 1_700_000_000_000L, "hello hermes", signature))
    }

    @Test
    fun `deriveSigningKey is deterministic for the same token`() {
        val first = HermesHmac.deriveSigningKey(token)
        val second = HermesHmac.deriveSigningKey(token)
        assertTrue(first.contentEquals(second))
    }

    // Known-answer vector produced by the gateway's own crypto.py — proves the two
    // implementations agree, not just that this side agrees with itself. The token
    // is 31 bytes (0x00..0x1e) as generated on the gateway side.
    @Test
    fun `matches the gateway's known-answer vector`() {
        val tokenBytes = HermesTokenCodec.decodeHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e")
        val key = HermesHmac.deriveSigningKey(tokenBytes)
        assertEquals("7f0926d4737e98f5c2f96a9581958400854c3344a876f33de7b4d2071e63912b", HermesTokenCodec.encodeHex(key))
        val mac = java.util.Base64.getEncoder().encodeToString(HermesHmac.sign(key, "test-id", 1_700_000_000_000L, "hello"))
        assertEquals("x9YXxisrCUIt3Ddz3kyk9fFa0QrFaJOwr2sA1pI5Wr8=", mac)
    }

    @Test
    fun `deriveSigningKey differs across distinct tokens`() {
        val a = HermesHmac.deriveSigningKey(token)
        val b = HermesHmac.deriveSigningKey("some-other-token".toByteArray(Charsets.UTF_8))
        assertNotEquals(a.toList(), b.toList())
    }
}
