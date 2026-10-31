package io.github.kahme247.ompweb.domain

import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The sessions list's filter bar: `Running`, `Unread` and an age preset.
 * Every active slot narrows the list further (logical AND). The chips are
 * persisted per server origin, so the state is a value object shared by the
 * settings store, the view model and the UI.
 */
data class SessionListFilter(
    val runningOnly: Boolean = false,
    val unreadOnly: Boolean = false,
    val age: SessionAgeFilter = SessionAgeFilter.All,
) {
    /** Nothing narrows the list; the stored origin entry is removed for this state. */
    val isDefault: Boolean get() = !runningOnly && !unreadOnly && age == SessionAgeFilter.All

    /** At least one chip narrows the list. */
    val active: Boolean get() = !isDefault
}

/**
 * Age presets of the filter bar, measured against a session's `modified`.
 *
 * `Today` is the local calendar day (the chip says "Today", so a session from
 * 23:59 yesterday is yesterday even at 00:01). `TwoDays` and `SevenDays` are
 * rolling windows, which is what "not older than 2 days" means. The cutoff is
 * inclusive: a session modified exactly at the cutoff stays visible.
 */
enum class SessionAgeFilter(val wireName: String) {
    All("all"),
    Today("today"),
    TwoDays("2d"),
    SevenDays("7d"),
    ;

    companion object {
        /** Unknown or missing values fall back to [All] (forward-compatible storage). */
        fun fromWire(value: String?): SessionAgeFilter =
            entries.firstOrNull { it.wireName == value } ?: All
    }
}

/**
 * The instant before which a session is too old for [filter]; `null` means
 * "no age limit". [now] and [zone] are injected so the boundaries (midnight,
 * exactly 48 h/7 d) are testable.
 */
fun SessionAgeFilter.cutoff(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant? =
    when (this) {
        SessionAgeFilter.All -> null
        SessionAgeFilter.Today -> now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        SessionAgeFilter.TwoDays -> now.minus(Duration.ofDays(2))
        SessionAgeFilter.SevenDays -> now.minus(Duration.ofDays(7))
    }

/** ISO-8601 instant as the server writes `modified`/`created` (space or offset forms accepted). */
fun parseSessionTimestamp(value: String): Instant? = try {
    Instant.parse(value)
} catch (_: Exception) {
    try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: Exception) {
        null
    }
}

/**
 * True when [session] survives every active chip. A session whose `modified`
 * cannot be read is kept while an age filter is active: live placeholders
 * carry no timestamp and an unreadable value is no proof that a session is
 * old.
 */
fun sessionPassesFilters(
    session: SessionSummary,
    filter: SessionListFilter,
    runningIds: Set<String>,
    unreadIds: Set<String>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    if (filter.runningOnly && session.id !in runningIds) return false
    if (filter.unreadOnly && session.id !in unreadIds) return false
    val cutoff = filter.age.cutoff(now, zone) ?: return true
    val modified = parseSessionTimestamp(session.modified) ?: return true
    return !modified.isBefore(cutoff)
}

/**
 * The Unread chip's sources, all keyed by session id: the sidebar's unread
 * markers, the sessions of unread notification-history entries, and the
 * session whose blocking input dialog is waiting for an answer.
 */
fun sidebarUnreadSessionIds(
    markerIds: Collection<String>,
    notificationSessionIds: Collection<String>,
    awaitingInputSessionIds: Collection<String> = emptyList(),
): Set<String> = buildSet {
    addAll(markerIds.filter { it.isNotBlank() })
    addAll(notificationSessionIds.filter { it.isNotBlank() })
    addAll(awaitingInputSessionIds.filter { it.isNotBlank() })
}

/** Session ids of the history entries the user has not read yet. */
fun unreadNotificationSessionIds(entries: List<NotificationHistoryEntry>): Set<String> =
    entries.asSequence()
        .filter { !it.read }
        .map { it.event.sessionId.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

/**
 * How many sessions the active chips hide. Pinned sessions are excluded: the
 * pinned section shows them regardless of the filters, so they are not
 * hidden. Search is not part of the count (the query is visible in its
 * field), and an inactive filter hides nothing.
 */
fun hiddenByFiltersCount(
    sessionsByProject: Map<String, List<SessionSummary>>,
    filter: SessionListFilter,
    runningIds: Set<String>,
    unreadIds: Set<String>,
    pinnedSessionIds: Collection<String> = emptyList(),
    now: Instant = Instant.EPOCH,
    zone: ZoneId = ZoneId.systemDefault(),
): Int {
    if (!filter.active) return 0
    val pinned = pinnedSessionIds.toSet()
    return sessionsByProject.values.sumOf { sessions ->
        sessions.count { session ->
            session.id !in pinned &&
                !sessionPassesFilters(session, filter, runningIds, unreadIds, now, zone)
        }
    }
}

/** Path comparison used by the pinned-project exemption (case-folded, like the pins). */
private fun projectIsPinned(project: ManagedProject, pinnedKeys: Set<String>): Boolean =
    comparableProjectPath(project.path) in pinnedKeys

/**
 * The sidebar's filtered project/session entries in project order.
 *
 * Filters combine (AND): the chips first, then the search term. A project
 * whose sessions all fail an active chip disappears from the list — except a
 * pinned project, which the user keeps on purpose (its header then shows that
 * no session matches). A project without any sessions stays visible while no
 * chip is active, so a new workspace can be started from it; with only a
 * search term active it stays when its label or alias matches, exactly like
 * the web.
 */
fun visibleSessionEntries(
    projects: List<ManagedProject>,
    sessionsByProject: Map<String, List<SessionSummary>>,
    runningOnly: Boolean,
    runningIds: Set<String>,
    query: String,
    unreadOnly: Boolean = false,
    unreadIds: Set<String> = emptySet(),
    age: SessionAgeFilter = SessionAgeFilter.All,
    now: Instant = Instant.EPOCH,
    zone: ZoneId = ZoneId.systemDefault(),
    pinnedProjectPaths: Collection<String> = emptyList(),
): List<Pair<ManagedProject, List<SessionSummary>>> {
    val filter = SessionListFilter(runningOnly, unreadOnly, age)
    val pinnedKeys = pinnedProjectPaths.mapTo(HashSet()) { comparableProjectPath(it) }
    val q = query.trim()
    val entries = ArrayList<Pair<ManagedProject, List<SessionSummary>>>(projects.size)
    for (project in projects) {
        var list = sessionsByProject[project.path].orEmpty()
        if (filter.active) {
            list = list.filter { sessionPassesFilters(it, filter, runningIds, unreadIds, now, zone) }
        }
        if (q.isNotEmpty()) list = list.filter { sessionMatchesSearch(it, q) }
        if (list.isEmpty()) {
            val pinnedProject = projectIsPinned(project, pinnedKeys)
            if (filter.active && !pinnedProject) continue
            if (!filter.active && q.isNotEmpty() && !projectMatchesSearch(project, q)) continue
        }
        entries += project to list
    }
    return entries
}
