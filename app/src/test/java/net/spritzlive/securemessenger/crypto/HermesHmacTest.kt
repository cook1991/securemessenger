package net.spritzlive.securemessenger.crypto

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

    @Test
    fun `deriveSigningKey differs across distinct tokens`() {
        val a = HermesHmac.deriveSigningKey(token)
        val b = HermesHmac.deriveSigningKey("some-other-token".toByteArray(Charsets.UTF_8))
        assertNotEquals(a.toList(), b.toList())
    }
}
