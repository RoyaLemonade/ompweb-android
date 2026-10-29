package io.github.kahme247.ompweb.domain

import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import java.time.Instant

/**
 * Pure ordering/grouping helpers shared by the sidebar.
 *
 * Kotlin port of `lib/project-ordering.ts` (plus `comparableProjectPath` from
 * `lib/comparable-path.ts` and `workspaceKeyOf` from
 * `lib/workspace-memory.ts`). All keys are canonical projectRoot paths
 * (worktrees collapse into their main repo via the server), so worktree
 * sessions group under their project.
 *
 * The project list follows activity: pinned projects first (in pin order),
 * then projects with a running chat, then by the newest session timestamp.
 * Because that order changes while the user looks at it, the rendered list is
 * frozen while the user interacts and only adopts a new order after
 * [PROJECT_ORDER_SETTLE_MILLIS] of quiet ([ProjectOrderHold]).
 */

private val WINDOWS_ABSOLUTE_RE = Regex("""^[a-zA-Z]:[\\/]""")
private val UNC_PREFIX_RE = Regex("^//")

/**
 * Case-insensitive comparable form on Windows-form paths (NTFS is
 * case-insensitive); case-sensitive for POSIX-form paths. Windows-ness is
 * decided by the path's own form, exactly like `lib/comparable-path.ts`.
 */
fun comparableProjectPath(value: String): String {
    val normalized = value.replace('\\', '/')
    val windowsForm = WINDOWS_ABSOLUTE_RE.containsMatchIn(normalized) || UNC_PREFIX_RE.containsMatchIn(normalized)
    val collapsed = Regex("/{2,}").replace(normalized, "/").trimEnd('/')
    return if (windowsForm) collapsed.lowercase() else collapsed
}

/** The bucket a session belongs to: projectKey, else projectRoot, else cwd. */
fun workspaceKeyOf(session: SessionSummary): String? =
    session.projectKey ?: session.projectRoot ?: session.cwd

/** How long the list must stay untouched before a new project order is shown. */
const val PROJECT_ORDER_SETTLE_MILLIS: Long = 1_500L

/**
 * Newest session timestamp per project (`modified`, epoch millis), keyed by
 * the case-folded comparable project path. A malformed timestamp is ignored,
 * so a broken session file never lifts its project to the top.
 */
fun projectLastActivity(sessions: List<SessionSummary>): Map<String, Long> {
    val result = LinkedHashMap<String, Long>()
    for (session in sessions) {
        val key = workspaceKeyOf(session) ?: continue
        val at = parseSessionInstant(session.modified) ?: continue
        val folded = comparableProjectPath(key)
        val current = result[folded]
        if (current == null || at > current) result[folded] = at
    }
    return result
}

private fun parseSessionInstant(value: String): Long? {
    if (value.isBlank()) return null
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

private class ProjectOrderEntry(
    val project: ManagedProject,
    /** Incoming position; the stable tie-break of every group. */
    val position: Int,
    val pin: Int?,
    val running: Boolean,
    val activity: Long,
)

/**
 * Activity order for the project list:
 *
 * 1. pinned projects, in [pinnedPaths] order (most recently pinned first),
 * 2. projects with a running session ([runningProjects], this server or
 *    another omp process),
 * 3. everything else by [lastActivity], newest first.
 *
 * Ties keep the incoming order, so equal timestamps and projects without
 * sessions never shuffle between refreshes.
 */
fun orderProjectsByActivity(
    projects: List<ManagedProject>,
    pinnedPaths: List<String>,
    runningProjects: Collection<String>,
    lastActivity: Map<String, Long>,
): List<ManagedProject> {
    if (projects.size < 2) return projects
    val pinIndex = HashMap<String, Int>()
    pinnedPaths.forEachIndexed { index, path ->
        val key = comparableProjectPath(path)
        if (key.isNotEmpty()) pinIndex.putIfAbsent(key, index)
    }
    val running = runningProjects.mapTo(HashSet(), ::comparableProjectPath)
    val entries = projects.mapIndexed { position, project ->
        val key = comparableProjectPath(project.path)
        ProjectOrderEntry(
            project = project,
            position = position,
            pin = pinIndex[key],
            running = key in running,
            activity = lastActivity[key] ?: Long.MIN_VALUE,
        )
    }
    return entries.sortedWith { a, b ->
        when {
            a.pin != null && b.pin != null -> {
                val byPin = a.pin.compareTo(b.pin)
                if (byPin != 0) byPin else a.position.compareTo(b.position)
            }
            a.pin != null -> -1
            b.pin != null -> 1
            a.running != b.running -> if (a.running) -1 else 1
            a.activity != b.activity -> if (a.activity > b.activity) -1 else 1
            else -> a.position.compareTo(b.position)
        }
    }.map { it.project }
}

/**
 * Re-orders [projects] into the frozen [orderPaths] without resurrecting
 * projects that left the list: projects named there come in that order,
 * everything else (a project just added, or one whose path casing changed)
 * follows in its incoming order.
 */
fun applyProjectOrder(projects: List<ManagedProject>, orderPaths: List<String>): List<ManagedProject> {
    if (projects.isEmpty() || orderPaths.isEmpty()) return projects
    val rank = HashMap<String, Int>()
    orderPaths.forEachIndexed { index, path ->
        rank.putIfAbsent(comparableProjectPath(path), index)
    }
    return projects.sortedWith(compareBy { rank[comparableProjectPath(it.path)] ?: Int.MAX_VALUE })
}

/**
 * Keeps the rendered project order stable while the user is busy.
 *
 * The list is frozen while [beginInteraction] has been called more often than
 * [endInteraction] (touch, scroll, drag, open row menu, active search field)
 * and for [settleMillis] after the last interaction ended. A new order is
 * adopted only afterwards, so rows never jump under a finger. Times are
 * passed in by the caller, which keeps the decision pure and testable.
 */
class ProjectOrderHold(private val settleMillis: Long = PROJECT_ORDER_SETTLE_MILLIS) {
    private var shown: List<String>? = null
    private var interactionDepth = 0
    private var interactionEndMillis: Long? = null

    val interacting: Boolean get() = interactionDepth > 0

    fun beginInteraction() {
        interactionDepth++
    }

    fun endInteraction(nowMillis: Long) {
        if (interactionDepth == 0) return
        interactionDepth--
        if (interactionDepth == 0) interactionEndMillis = nowMillis
    }

    /**
     * The order to render for [desired] at [nowMillis]: the frozen order while
     * interacting or settling, [desired] otherwise (and on the first call,
     * where there is nothing to freeze yet).
     */
    fun order(desired: List<String>, nowMillis: Long): List<String> {
        val current = shown
        if (current == null) {
            shown = desired
            return desired
        }
        if (isFrozen(nowMillis)) return current
        shown = desired
        return desired
    }

    /** Milliseconds until a pending settle window lifts, or null when none is pending. */
    fun settleDelayMillis(nowMillis: Long): Long? {
        if (interactionDepth > 0) return null
        if (interactionEndMillis == null) return null
        return (settleMillis - elapsedSinceEnd(nowMillis)).coerceAtLeast(0L)
    }

    private fun isFrozen(nowMillis: Long): Boolean {
        if (interactionDepth > 0) return true
        if (interactionEndMillis == null) return false
        if (elapsedSinceEnd(nowMillis) < settleMillis) return true
        interactionEndMillis = null
        return false
    }

    /**
     * Time since the last interaction ended, never negative. A wall clock that
     * jumps back must not stretch the settle window by the jump — the gate
     * would hold the order that much longer, which is exactly the "stuck gate"
     * the hold exists to avoid. The callers already pass a monotonic clock
     * where one is available.
     */
    private fun elapsedSinceEnd(nowMillis: Long): Long {
        val ended = interactionEndMillis ?: return 0L
        return (nowMillis - ended).coerceAtLeast(0L)
    }
}

/**
 * Whether the rendered list must hold its order right now: a finger on the
 * list or a scroll, an open row menu, or an active search. An open search
 * *panel* with an empty query is not enough — the whole unfiltered list would
 * freeze for as long as the panel stays visible.
 */
fun holdsProjectOrder(
    scrollInProgress: Boolean,
    menuOpen: Boolean,
    searchFocused: Boolean,
    searchQuery: String,
): Boolean = scrollInProgress || menuOpen || searchFocused || searchQuery.isNotBlank()

/** Running/unread/exited session counts for one project row. */
data class ProjectActivity(
    val running: Int = 0,
    val unread: Int = 0,
    val exited: Int = 0,
)

/**
 * Running/unread/exit session counts per project. Keys are the case-folded
 * comparable form of the project root, so casing-only differences
 * (Windows/NTFS) still resolve — look up with
 * `comparableProjectPath(project.path)`.
 */
fun projectActivityCounts(
    sessions: List<SessionSummary>,
    runningIds: Collection<String>,
    unreadIds: Collection<String>,
    exitedIds: Collection<String> = emptyList(),
): Map<String, ProjectActivity> {
    val running = runningIds.toSet()
    val unread = unreadIds.toSet()
    val exited = exitedIds.toSet()
    val result = LinkedHashMap<String, ProjectActivity>()
    for (session in sessions) {
        val key = workspaceKeyOf(session) ?: continue
        val folded = comparableProjectPath(key)
        val current = result[folded] ?: ProjectActivity()
        result[folded] = current.copy(
            running = current.running + if (session.id in running) 1 else 0,
            unread = current.unread + if (session.id in unread) 1 else 0,
            exited = current.exited + if (session.id in exited) 1 else 0,
        )
    }
    return result
}

/**
 * Group sessions under their project. Every project in [projects] gets an
 * entry (possibly empty) so empty managed projects render their empty state.
 * Buckets are keyed by the exact project path for callers; sessions are
 * matched through a case-folded lookup so casing-only differences land in the
 * right bucket instead of silently dropping the session.
 */
fun groupSessionsByProject(
    projects: List<ManagedProject>,
    sessions: List<SessionSummary>,
): Map<String, List<SessionSummary>> {
    val grouped = LinkedHashMap<String, MutableList<SessionSummary>>()
    val bucketByKey = HashMap<String, MutableList<SessionSummary>>()
    for (project in projects) {
        val bucket = mutableListOf<SessionSummary>()
        grouped[project.path] = bucket
        bucketByKey[comparableProjectPath(project.path)] = bucket
    }
    for (session in sessions) {
        val key = workspaceKeyOf(session) ?: continue
        bucketByKey[comparableProjectPath(key)]?.add(session)
    }
    return grouped
}
