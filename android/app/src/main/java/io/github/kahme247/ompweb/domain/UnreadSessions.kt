package io.github.kahme247.ompweb.domain

/**
 * Unread badge bookkeeping for sidebar sessions.
 *
 * Semantics (web `components/SessionSidebar*.tsx` and `useSessions`): a
 * session that is running when the user is not reading it and then stops
 * becomes unread; starting to run again clears the badge. The set is pruned
 * to sessions that still exist so deleted files cannot leave permanent dots.
 *
 * A completion that merely arrives late is not news. The web compares the
 * running set it last saw with the next one, which marks the same completion
 * again when a reconnect, a deferred list reload or a stale snapshot replays
 * it. [readActivityAt] and [activityAt] close that hole: both hold message
 * times (server clock, epoch millis) — the newest one the user had on screen
 * when the chat was viewed, and the newest one the app knows now. A session
 * whose activity is not newer than what was read keeps its badge off; a new
 * run clears its read marker, so its end still becomes unread.
 */

fun applyRunningTransition(
    previousRunning: Set<String>,
    running: Set<String>,
    selectedSessionId: String?,
    unread: Set<String>,
    /** Newest message shown per session when it was last viewed, server clock. */
    readActivityAt: Map<String, Long> = emptyMap(),
    /** Newest message the app knows per session now, server clock. */
    activityAt: Map<String, Long> = emptyMap(),
): Set<String> {
    val completed = previousRunning.filterTo(mutableSetOf()) { id ->
        id !in running && id != selectedSessionId && completesUnreadActivity(id, readActivityAt, activityAt)
    }
    val newlyRunning = running.filter { it !in previousRunning }
    if (completed.isEmpty() && newlyRunning.isEmpty()) return unread
    val next = unread.toMutableSet()
    newlyRunning.forEach(next::remove)
    completed.forEach(next::add)
    return next
}

fun pruneUnread(unread: Set<String>, existingIds: Collection<String>): Set<String> {
    val existing = existingIds.toSet()
    return unread.filterTo(linkedSetOf()) { it in existing }
}

/**
 * True when an ending run is news: either no activity was read for this
 * session yet, or the newest activity the app knows is **after** the one that
 * was on screen when the chat was last viewed.
 *
 * An unknown activity time with a read marker suppresses the badge: the
 * marker itself can only have been recorded from a known message time, so a
 * later absence of evidence means nothing new arrived. Both timestamps come
 * from the session (its `lastMessageAt` or the transcript's message times),
 * never from the device clock and never from the session file's `modified` —
 * opening a chat rewrites that file and would otherwise mark it unread again.
 */
fun completesUnreadActivity(
    sessionId: String,
    readActivityAt: Map<String, Long>,
    activityAt: Map<String, Long>,
): Boolean {
    if (sessionId !in readActivityAt) return true
    return activityIsAfterRead(sessionId, readActivityAt, activityAt)
}

/** True when [sessionId] has activity newer than the newest one the user read. */
fun activityIsAfterRead(
    sessionId: String,
    readActivityAt: Map<String, Long>,
    activityAt: Map<String, Long>,
): Boolean {
    val read = readActivityAt[sessionId] ?: return false
    val activity = activityAt[sessionId] ?: return false
    return activity > read
}
