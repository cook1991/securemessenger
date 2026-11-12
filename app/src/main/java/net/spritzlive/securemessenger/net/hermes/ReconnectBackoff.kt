package net.spritzlive.securemessenger.net.hermes

/**
 * Pure state machine for the Hermes link's reconnect delay — kept Android-free so
 * the schedule itself is unit-testable without a Service/Looper in the loop.
 * Exponential 1s -> 60s cap; a connection has to stay up for [stableMs] before the
 * next drop goes back to [initialMs] instead of continuing to climb from wherever
 * backoff last left off.
 */
class ReconnectBackoff(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 60_000L,
    private val stableMs: Long = 10_000L,
) {
    private var currentDelayMs = initialMs

    /** The delay to wait before the next attempt; doubles (capped) for the attempt after that. */
    fun nextDelayMs(): Long {
        val delay = currentDelayMs
        currentDelayMs = (currentDelayMs * 2).coerceAtMost(maxMs)
        return delay
    }

    /** Call once a connection has been open for [stableMs] without dropping. */
    fun reset() {
        currentDelayMs = initialMs
    }

    /** How long (ms) a connection must stay open before [reset] should fire. */
    fun stableThresholdMs(): Long = stableMs
}
