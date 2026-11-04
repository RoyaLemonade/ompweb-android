package io.github.kahme247.ompweb.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unread semantics used by the running-frame monitor. */
class UnreadSessionsTest {

    @Test
    fun `a session that starts running loses its unread badge`() {
        assertEquals(
            emptySet<String>(),
            applyRunningTransition(
                previousRunning = emptySet(),
                running = setOf("a"),
                selectedSessionId = null,
                unread = setOf("a"),
            ),
        )
    }

    @Test
    fun `a session that stops running while unselected becomes unread`() {
        assertEquals(
            setOf("a"),
            applyRunningTransition(
                previousRunning = setOf("a"),
                running = emptySet(),
                selectedSessionId = null,
                unread = emptySet(),
            ),
        )
    }

    @Test
    fun `a session that stops while selected stays read`() {
        assertEquals(
            emptySet<String>(),
            applyRunningTransition(
                previousRunning = setOf("a"),
                running = emptySet(),
                selectedSessionId = "a",
                unread = emptySet(),
            ),
        )
    }

    @Test
    fun `unrelated transitions keep the existing badges`() {
        val unread = setOf("old")
        assertEquals(
            setOf("old"),
            applyRunningTransition(
                previousRunning = setOf("a"),
                running = setOf("a"),
                selectedSessionId = null,
                unread = unread,
            ),
        )
    }

    @Test
    fun `a completion whose activity was not newer than the read chat stays silent`() {
        // The run ended while the chat was open and the app only sees the
        // transition afterwards: the newest message is the one the chat showed.
        assertEquals(
            emptySet<String>(),
            applyRunningTransition(
                previousRunning = setOf("a"),
                running = emptySet(),
                selectedSessionId = null,
                unread = emptySet(),
                readActivityAt = mapOf("a" to 2_000L),
                activityAt = mapOf("a" to 2_000L),
            ),
        )
    }

    @Test
    fun `a completion with newer activity than the read chat becomes unread`() {
        assertEquals(
            setOf("a"),
            applyRunningTransition(
                previousRunning = setOf("a"),
                running = emptySet(),
                selectedSessionId = null,
                unread = emptySet(),
                readActivityAt = mapOf("a" to 2_000L),
                activityAt = mapOf("a" to 5_000L),
            ),
        )
    }

    @Test
    fun `a read chat without a known activity time keeps the badge off`() {
        // The marker can only come from a message time; no newer evidence
        // means nothing new arrived (an older server, a failed transcript).
        assertEquals(
            emptySet<String>(),
            applyRunningTransition(
                previousRunning = setOf("a"),
                running = emptySet(),
                selectedSessionId = null,
                unread = emptySet(),
                readActivityAt = mapOf("a" to 2_000L),
                activityAt = emptyMap(),
            ),
        )
    }

    @Test
    fun `starting a new run drops the read marker for its end`() {
        // A new run is unread activity: its completion is news even though the
        // session was read before. The view model clears the marker on the
        // newly-running transition; this pins the rule the transition relies on.
        val cleared = mapOf("a" to 2_000L) - "a"
        assertEquals(
            setOf("a"),
            applyRunningTransition(
                previousRunning = setOf("a"),
                running = emptySet(),
                selectedSessionId = null,
                unread = emptySet(),
                readActivityAt = cleared,
                activityAt = mapOf("a" to 5_000L),
            ),
        )
    }

    @Test
    fun `a new run clears an old badge even with a read marker`() {
        assertEquals(
            emptySet<String>(),
            applyRunningTransition(
                previousRunning = emptySet(),
                running = setOf("a"),
                selectedSessionId = null,
                unread = setOf("a"),
                readActivityAt = mapOf("a" to 2_000L),
                activityAt = mapOf("a" to 3_000L),
            ),
        )
    }

    @Test
    fun `pruning drops ids that no longer exist and keeps order`() {
        assertEquals(
            listOf("b", "c"),
            pruneUnread(setOf("zombie", "b", "c"), listOf("a", "b", "c")).toList(),
        )
    }
}
