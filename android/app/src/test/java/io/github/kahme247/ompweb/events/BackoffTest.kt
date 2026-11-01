package io.github.kahme247.ompweb.events

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconnect cap follows the window state: 30 s is what a user watching the
 * app waits through, five minutes is what a backgrounded phone may sleep
 * through without waking the radio every half minute.
 */
class BackoffTest {

    private fun backoff() = Backoff(
        baseMillis = 1_000,
        maxMillis = 30_000,
        jitterRatio = 0.0,
        random = Random(0),
    )

    @Test
    fun `the foreground cap stays at thirty seconds`() {
        val backoff = backoff()

        assertEquals(1_000L, backoff.delayMillis(0))
        assertEquals(2_000L, backoff.delayMillis(1))
        assertEquals(16_000L, backoff.delayMillis(4))
        assertEquals("32 s is capped", 30_000L, backoff.delayMillis(5))
        assertEquals(30_000L, backoff.delayMillis(9))
        assertEquals("the cap holds for a long outage", 30_000L, backoff.delayMillis(100))
    }

    @Test
    fun `the background cap grows to five minutes`() {
        val backoff = backoff()

        assertEquals(1_000L, backoff.delayMillis(0, background = true))
        assertEquals(2_000L, backoff.delayMillis(1, background = true))
        assertEquals(16_000L, backoff.delayMillis(4, background = true))
        assertEquals("past the foreground cap", 32_000L, backoff.delayMillis(5, background = true))
        assertEquals(256_000L, backoff.delayMillis(8, background = true))
        assertEquals(300_000L, backoff.delayMillis(9, background = true))
        assertEquals(300_000L, backoff.delayMillis(100, background = true))
        assertEquals(Backoff.BACKGROUND_MAX_MILLIS, backoff.delayMillis(100, background = true))
    }

    @Test
    fun `the window state alone decides which cap applies`() {
        val backoff = backoff()

        // Same attempt, two states: the background delay is never shorter than
        // the foreground one, and only it passes the foreground cap.
        for (attempt in 0..12) {
            assertTrue(
                "attempt $attempt",
                backoff.delayMillis(attempt, background = true) >= backoff.delayMillis(attempt),
            )
        }
        assertEquals(30_000L, backoff.delayMillis(12))
        assertEquals(300_000L, backoff.delayMillis(12, background = true))
    }

    @Test
    fun `the jitter stays inside the cap of the current state`() {
        val backoff = Backoff(baseMillis = 1_000, maxMillis = 30_000, jitterRatio = 0.2, random = Random(42))

        assertTrue(backoff.delayMillis(0) in 800..1_200)
        assertTrue(backoff.delayMillis(6) in 24_000..30_000)
        assertTrue(
            "never beyond the background cap",
            backoff.delayMillis(12, background = true) in 240_000..300_000,
        )
        assertTrue("never beyond the foreground cap", backoff.delayMillis(12) in 24_000..30_000)
    }
}
