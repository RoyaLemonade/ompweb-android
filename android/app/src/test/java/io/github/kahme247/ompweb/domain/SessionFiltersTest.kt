package io.github.kahme247.ompweb.domain

import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter bar's pure logic: AND-combination, the age boundaries against an
 * injected clock (midnight, exactly 48 h/7 d, unknown timestamps), the pinned
 * exemptions and the three unread sources.
 */
class SessionFiltersTest {

    private val zone = ZoneId.of("UTC")
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
    ) = sessionPassesFilters(session, filter, runningIds, unreadIds, at, zone)

    @Test
    fun `today starts at local midnight, not 24 hours ago`() {
        val justAfterMidnight = Instant.parse("2026-03-10T00:00:30Z")
        val today = filter(age = SessionAgeFilter.Today)

        assertTrue(
            "midnight itself belongs to today",
            passes(session("midnight", "2026-03-10T00:00:00Z"), today, at = justAfterMidnight),
        )
        assertFalse(
            "23:59 the day before is yesterday even two minutes ago",
            passes(session("late", "2026-03-09T23:59:59Z"), today, at = justAfterMidnight),
        )
        assertTrue(
            "a session from this morning passes",
            passes(session("morning", "2026-03-10T09:00:00Z"), today, at = justAfterMidnight),
        )
    }

    @Test
    fun `two days and seven days are rolling windows with inclusive cutoffs`() {
        val twoDays = filter(age = SessionAgeFilter.TwoDays)
        val exact = session("exact", now.minusSeconds(2 * 86_400).toString())
        val tooOld = session("old", now.minusSeconds(2 * 86_400 + 1).toString())
        assertTrue("exactly 48 h old still counts", passes(exact, twoDays))
        assertFalse("one millisecond past 48 h does not", passes(tooOld, twoDays))

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
            passes(session("future", "2026-03-10T13:00:00Z"), filter(age = SessionAgeFilter.Today)),
        )
        assertTrue(
            "a live placeholder carries no timestamp and has to stay visible",
            passes(session("placeholder", modified = ""), filter(age = SessionAgeFilter.Today)),
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

        val filter = filter(runningOnly = true, unreadOnly = true, age = SessionAgeFilter.Today)
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
            age = SessionAgeFilter.Today,
            now = now,
            zone = zone,
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
        val filter = filter(age = SessionAgeFilter.Today)

        val entries = visibleSessionEntries(
            projects = listOf(ManagedProject("/work/alpha")),
            sessionsByProject = byProject,
            runningOnly = false,
            runningIds = emptySet(),
            query = "",
            age = filter.age,
            now = now,
            zone = zone,
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
                zone = zone,
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
            age = SessionAgeFilter.Today,
            now = now,
            zone = zone,
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
            age = SessionAgeFilter.Today,
            now = now,
            zone = zone,
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
        assertFalse(SessionListFilter(age = SessionAgeFilter.Today).isDefault)
        assertEquals(
            listOf(SessionAgeFilter.All, SessionAgeFilter.Today, SessionAgeFilter.TwoDays, SessionAgeFilter.SevenDays),
            SessionAgeFilter.entries,
        )
        SessionAgeFilter.entries.forEach { assertEquals(it, SessionAgeFilter.fromWire(it.wireName)) }
        assertEquals(SessionAgeFilter.All, SessionAgeFilter.fromWire("tomorrow"))
        assertEquals(SessionAgeFilter.All, SessionAgeFilter.fromWire(null))
    }
}
