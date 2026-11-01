package io.github.kahme247.ompweb.events

import kotlin.random.Random

/**
 * Exponential backoff with jitter for event-stream reconnects: 1 s, 2 s, 4 s
 * … with ±20 % jitter so a fleet of clients that lost a server at the same
 * moment does not reconnect in lockstep.
 *
 * The cap follows the app's window state. In the foreground a reconnect is
 * what the user is waiting for, so the delay stops at [maxMillis]. A
 * backgrounded client still has to stay reachable for notifications, but a
 * server that is switched off must not keep its radio busy all day: there the
 * delay keeps doubling up to [backgroundMaxMillis]. Returning to the
 * foreground retries at once and starts the count over
 * (`EventMux.onForeground`).
 */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val maxMillis: Long = 30_000,
    private val backgroundMaxMillis: Long = BACKGROUND_MAX_MILLIS,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    fun delayMillis(attempt: Int, background: Boolean = false): Long {
        val cap = if (background) backgroundMaxMillis else maxMillis
        var capped = baseMillis
        repeat(attempt.coerceIn(0, 30)) {
            capped = (capped * 2).coerceAtMost(cap)
        }
        val factor = 1.0 + (random.nextDouble() * 2 - 1) * jitterRatio
        return (capped * factor).toLong().coerceIn(1, cap)
    }

    companion object {
        /**
         * Five minutes: long enough not to wake the radio every half minute, and
         * short enough to pick the stream up again after a laptop woke up
         * without the user touching the phone.
         */
        const val BACKGROUND_MAX_MILLIS = 5 * 60_000L
    }
}
