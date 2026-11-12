package net.spritzlive.securemessenger.net.hermes

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun `delay doubles each attempt starting from the initial value`() {
        val backoff = ReconnectBackoff(initialMs = 1_000L, maxMs = 60_000L, stableMs = 10_000L)
        assertEquals(1_000L, backoff.nextDelayMs())
        assertEquals(2_000L, backoff.nextDelayMs())
        assertEquals(4_000L, backoff.nextDelayMs())
        assertEquals(8_000L, backoff.nextDelayMs())
    }

    @Test
    fun `delay is capped at maxMs and does not exceed it`() {
        val backoff = ReconnectBackoff(initialMs = 1_000L, maxMs = 60_000L, stableMs = 10_000L)
        repeat(10) { backoff.nextDelayMs() }
        assertEquals(60_000L, backoff.nextDelayMs())
        assertEquals(60_000L, backoff.nextDelayMs())
    }

    @Test
    fun `reset returns the next delay to the initial value`() {
        val backoff = ReconnectBackoff(initialMs = 1_000L, maxMs = 60_000L, stableMs = 10_000L)
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.nextDelayMs()
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun `stableThresholdMs reports the configured value`() {
        val backoff = ReconnectBackoff(initialMs = 1_000L, maxMs = 60_000L, stableMs = 10_000L)
        assertEquals(10_000L, backoff.stableThresholdMs())
    }
}
