package io.github.kahme247.ompweb.domain

import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter bar's pure logic: AND-combination, the age boundaries against an
 * injected clock (23:59 h/24:01 h, exactly 48 h/7 d, midnight, unknown
 * timestamps), the stored wire names with their 1.0.35 migration, the pinned
 * exemptions and the three unread sources.
 */
class SessionFiltersTest {

    private val now = Instant.parse("2026-03-10T12:00:00Z")

    private fun session(
        id: String,
        modified: String = "2026-03-10T11:00:00Z",
        cwd: String = "/work/alpha",
    ) = SessionSummary(
        id = id,
        cwd = cwd,
        modified = modified,
        messageCount = 1,
        firstMessage = "hi",
    )

    private fun filter(
        runningOnly: Boolean = false,
        unreadOnly: Boolean = false,
        age: SessionAgeFilter = SessionAgeFilter.All,
    ) = SessionListFilter(runningOnly, unreadOnly, age)

    private fun passes(
        session: SessionSummary,
        filter: SessionListFilter,
        runningIds: Set<String> = emptySet(),
        unreadIds: Set<String> = emptySet(),
        at: Instant = now,
    ) = sessionPassesFilters(session, filter, runningIds, unreadIds, at)

    @Test
    fun `24 hours is a rolling window, not the calendar day`() {
        val window = filter(age = SessionAgeFilter.Last24Hours)

        assertTrue(
            "23:59 h old is inside the window",
            passes(session("inside", now.minusSeconds(23 * 3_600 + 59 * 60).toString()), window),
        )
        assertTrue(
            "exactly 24 h old still counts (inclusive cutoff)",
            passes(session("edge", now.minusSeconds(24 * 3_600).toString()), window),
        )
        assertFalse(
            "24:01 h old is outside the window",
            passes(session("outside", now.minusSeconds(24 * 3_600 + 60).toString()), window),
        )

        // 00:05 is only five minutes after midnight; a chat from 23:58 the
        // evening before is seven minutes old and stays in the 24 h list,
        // although it belongs to "yesterday".
        val justAfterMidnight = Instant.parse("2026-03-10T00:05:00Z")
        assertTrue(
            "crossing midnight does not drop what is younger than 24 h",
            passes(session("lateTheDayBefore", "2026-03-09T23:58:00Z"), window, at = justAfterMidnight),
        )
        assertFalse(
            "an evening that is more than 24 h ago is out",
            passes(session("eveningBefore", "2026-03-08T23:00:00Z"), window, at = justAfterMidnight),
        )
        // Late in the day the window reaches into the same calendar day:
        // 00:10 is 23 h 20 min old at 23:30 and stays in, 23:00 the evening
        // before is 24 h 30 min old and leaves.
        val lateEvening = Instant.parse("2026-03-10T23:30:00Z")
        assertTrue(
            "the window covers the calendar day it reaches into",
            passes(session("earlyToday", "2026-03-10T00:10:00Z"), window, at = lateEvening),
        )
        assertFalse(
            "24 h 30 min old is out",
            passes(session("yesterdayEvening", "2026-03-09T23:00:00Z"), window, at = lateEvening),
        )
    }

    @Test
    fun `48 hours and seven days are rolling windows with inclusive cutoffs`() {
        val twoDays = filter(age = SessionAgeFilter.Last48Hours)
        val exact = session("exact", now.minusSeconds(2 * 86_400).toString())
        val tooOld = session("old", now.minusSeconds(2 * 86_400 + 1).toString())
        assertTrue("exactly 48 h old still counts", passes(exact, twoDays))
        assertFalse("one second past 48 h does not", passes(tooOld, twoDays))

        val sevenDays = filter(age = SessionAgeFilter.SevenDays)
        assertTrue(
            "exactly 7 d old still counts",
            passes(session("week", now.minusSeconds(7 * 86_400).toString()), sevenDays),
        )
        assertFalse(
            "older than 7 d is hidden",
            passes(session("older", now.minusSeconds(7 * 86_400 + 1).toString()), sevenDays),
        )
    }

    @Test
    fun `a future or unreadable modified value never disappears unexpectedly`() {
        assertTrue(
            "clock skew must not hide a session",
            passes(session("future", "2026-03-10T13:00:00Z"), filter(age = SessionAgeFilter.Last24Hours)),
        )
        assertTrue(
            "a live placeholder carries no timestamp and has to stay visible",
            passes(session("placeholder", modified = ""), filter(age = SessionAgeFilter.Last24Hours)),
        )
        assertTrue(
            "an unreadable value is no proof of age",
            passes(session("broken", modified = "not-a-timestamp"), filter(age = SessionAgeFilter.SevenDays)),
        )
    }

    @Test
    fun `chips combine with AND`() {
        val running = session("run", "2026-03-10T11:00:00Z")
        val unread = session("read", "2026-03-10T11:00:00Z")
        val both = session("both", "2026-03-10T11:00:00Z")
        val oldBoth = session("old-both", "2026-03-01T11:00:00Z")

        val filter = filter(runningOnly = true, unreadOnly = true, age = SessionAgeFilter.Last24Hours)
        val runningIds = setOf("run", "both", "old-both")
        val unreadIds = setOf("read", "both", "old-both")

        assertTrue(passes(both, filter, runningIds, unreadIds))
        assertFalse("running alone is not enough", passes(running, filter, runningIds, unreadIds))
        assertFalse("unread alone is not enough", passes(unread, filter, runningIds, unreadIds))
        assertFalse("both chips but too old", passes(oldBoth, filter, runningIds, unreadIds))
    }

    @Test
    fun `projects without a session under the active chips collapse, except a pinned one`() {
        val alpha = ManagedProject("/work/alpha")
        val pinned = ManagedProject("/work/pinned")
        val projects = listOf(alpha, pinned)
        val byProject = mapOf(
            "/work/alpha" to listOf(session("a1", "2026-03-01T00:00:00Z")),
            "/work/pinned" to listOf(session("p1", "2026-03-01T00:00:00Z", cwd = "/work/pinned")),
        )

        val entries = visibleSessionEntries(
            projects = projects,
            sessionsByProject = byProject,
            runningOnly = false,
            runningIds = emptySet(),
            query = "",
            age = SessionAgeFilter.Last24Hours,
            now = now,
            pinnedProjectPaths = listOf("/work/pinned"),
        )

        assertEquals("only the pinned project survives a filter that hides all sessions", listOf("/work/pinned"), entries.map { it.first.path })
        assertTrue("its sessions stay filtered, so the header says so at render time", entries.single().second.isEmpty())
    }

    @Test
    fun `pinned sessions are exempt from the hidden count and visible through the filter`() {
        val byProject = mapOf(
            "/work/alpha" to listOf(
                session("old-pinned", "2026-03-01T00:00:00Z"),
                session("old", "2026-03-01T00:00:00Z"),
                session("fresh", "2026-03-10T11:00:00Z"),
            ),
        )
        val filter = filter(age = SessionAgeFilter.Last24Hours)

        val entries = visibleSessionEntries(
            projects = listOf(ManagedProject("/work/alpha")),
            sessionsByProject = byProject,
            runningOnly = false,
            runningIds = emptySet(),
            query = "",
            age = filter.age,
            now = now,
        )
        assertEquals("only the fresh session is listed in its project", listOf("fresh"), entries.single().second.map { it.id })

        assertEquals(
            "the pinned old session is shown above, so it is not hidden",
            1,
            hiddenByFiltersCount(
                sessionsByProject = byProject,
                filter = filter,
                runningIds = emptySet(),
                unreadIds = emptySet(),
                pinnedSessionIds = listOf("old-pinned"),
                now = now,
            ),
        )
    }

    @Test
    fun `search combines with the chips and keeps a label match while no chip is active`() {
        val projects = listOf(
            ManagedProject("/work/quantum-lab"),
            ManagedProject("/work/other", alias = "Mein Projekt"),
        )
        val byProject = mapOf(
            "/work/quantum-lab" to listOf(
                session("q-old", "2026-03-01T00:00:00Z").copy(name = "quantum old"),
                session("q-new", "2026-03-10T11:00:00Z").copy(name = "quantum release"),
            ),
            "/work/other" to emptyList(),
        )

        val fresh = visibleSessionEntries(
            projects = projects,
            sessionsByProject = byProject,
            runningOnly = false,
            runningIds = emptySet(),
            query = "quantum",
            age = SessionAgeFilter.Last24Hours,
            now = now,
        )
        assertEquals(listOf("/work/quantum-lab"), fresh.map { it.first.path })
        assertEquals("both the age chip and the query apply", listOf("q-new"), fresh.single().second.map { it.id })

        // The label may match the query, but an active chip still collapses a
        // project whose sessions all fail it.
        val collapsed = visibleSessionEntries(
            projects = listOf(ManagedProject("/work/quantum-lab")),
            sessionsByProject = mapOf(
                "/work/quantum-lab" to listOf(
                    session("q-old", "2026-03-01T00:00:00Z").copy(name = "quantum old"),
                ),
            ),
            runningOnly = false,
            runningIds = emptySet(),
            query = "quantum",
            age = SessionAgeFilter.Last24Hours,
            now = now,
        )
        assertTrue("a chip, not the label, decides visibility", collapsed.isEmpty())

        val labelOnly = visibleSessionEntries(
            projects = projects,
            sessionsByProject = byProject,
            runningOnly = false,
            runningIds = emptySet(),
            query = "mein projekt",
        )
        assertEquals(
            "without a chip, a matching workspace name keeps its empty header (web rule)",
            listOf("/work/other"),
            labelOnly.map { it.first.path },
        )
    }

    @Test
    fun `unread sources are the marker, the unread notification and the waiting input dialog`() {
        val entries = listOf(
            NotificationHistoryEntry(
                id = "s1:completed",
                event = NotificationHistoryEvent(NotificationHistoryType.COMPLETED, "s1"),
                at = 1L,
                read = false,
            ),
            NotificationHistoryEntry(
                id = "s2:completed",
                event = NotificationHistoryEvent(NotificationHistoryType.COMPLETED, "s2"),
                at = 2L,
                read = true,
            ),
            NotificationHistoryEntry(
                id = "s3:input",
                event = NotificationHistoryEvent(NotificationHistoryType.INPUT, "  "),
                at = 3L,
                read = false,
            ),
        )

        val notified = unreadNotificationSessionIds(entries)
        assertEquals("only unread entries with a session id count", setOf("s1"), notified)

        val unread = sidebarUnreadSessionIds(
            markerIds = listOf("marker", "s1", ""),
            notificationSessionIds = notified,
            awaitingInputSessionIds = listOf("dialog"),
        )
        assertEquals(setOf("marker", "s1", "dialog"), unread)
    }

    @Test
    fun `the default filter is inactive and round-trips through the wire names`() {
        assertTrue(SessionListFilter().isDefault)
        assertFalse(SessionListFilter(unreadOnly = true).isDefault)
        assertFalse(SessionListFilter(age = SessionAgeFilter.Last24Hours).isDefault)
        assertEquals(
            listOf(
                SessionAgeFilter.All,
                SessionAgeFilter.Last24Hours,
                SessionAgeFilter.Last48Hours,
                SessionAgeFilter.SevenDays,
            ),
            SessionAgeFilter.entries,
        )
        SessionAgeFilter.entries.forEach { assertEquals(it, SessionAgeFilter.fromWire(it.wireName)) }
        assertEquals(
            "a 1.0.34 `today` filter becomes the 24 h window",
            SessionAgeFilter.Last24Hours,
            SessionAgeFilter.fromWire("today"),
        )
        assertEquals(
            "a 1.0.34 `2d` filter becomes the 48 h window",
            SessionAgeFilter.Last48Hours,
            SessionAgeFilter.fromWire("2d"),
        )
        assertEquals(SessionAgeFilter.All, SessionAgeFilter.fromWire("tomorrow"))
        assertEquals(SessionAgeFilter.All, SessionAgeFilter.fromWire(null))
    }
}
