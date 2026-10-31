package io.github.kahme247.ompweb.ui.sessions

import io.github.kahme247.ompweb.domain.comparableProjectPath
import io.github.kahme247.ompweb.domain.workspaceKeyOf
import io.github.kahme247.ompweb.net.ExitedSession
import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary

/**
 * The session rows as the list renders them.
 *
 * [placeholders] is carried separately because only those rows may add a
 * project row on the client (see [withPlaceholderProjects]).
 */
internal class SessionListDisplay(
    /** `GET /api/sessions` rows, plus [placeholders]. */
    val sessions: List<SessionSummary>,
    /** Live rows that have no JSONL yet, so the server list cannot carry them. */
    val placeholders: List<SessionSummary>,
)

/**
 * Adds a placeholder row for every live session that has no JSONL yet.
 *
 * `GET /api/sessions` only lists sessions with a file, but a session started
 * from the app is live for a moment before its first message is written. The
 * web keeps such rows (`SessionSidebar.tsx` placeholders from
 * `runningSessionIds` + `exitedSessions`), so the new session stays visible
 * instead of vanishing until the next file change.
 */
internal fun withLivePlaceholders(
    sessions: List<SessionSummary>,
    runningIds: Set<String>,
    runningCwds: Map<String, String>,
    exited: Map<String, ExitedSession>,
    projects: List<ManagedProject>,
    activeCwd: String?,
): SessionListDisplay {
    val known = sessions.mapTo(HashSet<String>()) { it.id }
    val live = LinkedHashSet<String>().apply {
        addAll(runningIds)
        addAll(exited.keys)
    }
    val placeholders = live.filterNot { it in known }.map { id ->
        val cwd = runningCwds[id] ?: exited[id]?.cwd ?: activeCwd.orEmpty()
        val registered = projects.firstOrNull {
            cwd.isNotBlank() && comparableProjectPath(it.path) == comparableProjectPath(cwd)
        }?.path
        val root = registered ?: cwd
        SessionSummary(
            id = id,
            cwd = cwd,
            projectRoot = root.takeIf { it.isNotBlank() },
            projectKey = comparableProjectPath(root).takeIf { root.isNotBlank() },
        )
    }
    return SessionListDisplay(sessions + placeholders, placeholders)
}

/**
 * Projects for the list: the managed list plus a row for every live
 * placeholder whose directory it does not cover.
 *
 * Only placeholders can need one — `groupSessionsByProject` drops sessions
 * whose workspace has no row, so a placeholder in an unregistered directory
 * would disappear without this. A file-backed session never needs a row of its
 * own: `GET /api/projects` already merges session-discovered project roots and,
 * by contract, excludes the ones the user hid. Deriving rows from file-backed
 * sessions therefore resurrected exactly the hidden projects, because hiding
 * marks the registry entry and leaves the sessions on disk (and in
 * `GET /api/sessions`).
 */
internal fun withPlaceholderProjects(
    projects: List<ManagedProject>,
    display: SessionListDisplay,
): List<ManagedProject> {
    val known = projects.mapTo(HashSet()) { comparableProjectPath(it.path) }
    val extra = display.placeholders.mapNotNull(::workspaceKeyOf)
        .distinct()
        .filter { comparableProjectPath(it) !in known }
        .map { path -> ManagedProject(path = path) }
    return projects + extra
}
