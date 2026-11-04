package io.github.kahme247.ompweb.ui.notifications

import io.github.kahme247.ompweb.domain.NotificationHistoryEntry
import io.github.kahme247.ompweb.domain.NotificationHistorySync
import io.github.kahme247.ompweb.domain.SnapshotDecision
import io.github.kahme247.ompweb.domain.markNotificationHistoryRead
import io.github.kahme247.ompweb.domain.mergeDeliveredNotification
import io.github.kahme247.ompweb.domain.notificationHistoryEntryFromEvent
import io.github.kahme247.ompweb.domain.notificationReadIds
import io.github.kahme247.ompweb.domain.removeNotificationHistoryEntry
import io.github.kahme247.ompweb.domain.unreadNotificationCount
import io.github.kahme247.ompweb.net.NotificationHistoryApi
import io.github.kahme247.ompweb.net.captureApi
import io.github.kahme247.ompweb.net.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** What the Notifications tab and the bell render. */
data class NotificationHistoryState(
    /** Newest first, exactly the server's order (a delivered event goes to the front). */
    val entries: List<NotificationHistoryEntry> = emptyList(),
    val loading: Boolean = false,
    /** The last load's failure, shown as a retry banner; optimistic changes stay visible. */
    val error: String? = null,
) {
    val unreadCount: Int get() = unreadNotificationCount(entries)
}

/**
 * The persisted notification history (web `lib/notification-history.ts`): the
 * server owns the list, this view model mirrors it with optimistic changes.
 *
 * Read/merge/rollback rules:
 * - [onDelivered] adds the event straight to the list, no refetch; the server
 *   has already recorded it, and the next [refresh] adopts its timestamps.
 * - [markRead], [markAllRead], [remove] and [clearAll] change the list first and
 *   send the request afterwards. A failed request is not retried locally: the
 *   list is reloaded, so the server's state is what stays on screen.
 * - A load that raced a local change, or a change request still in flight, is
 *   not applied; it reloads once those requests settle
 *   ([NotificationHistorySync]).
 * - The pane calls [onListOpened]/[onListClosed]; closing marks exactly the
 *   entries it showed, so a notification that arrives after the close stays
 *   unread.
 */
class NotificationHistoryViewModel(
    private val api: NotificationHistoryApi,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(NotificationHistoryState())
    val state: StateFlow<NotificationHistoryState> = _state.asStateFlow()

    private val sync = NotificationHistorySync()
    private var loadsInFlight = 0

    /**
     * Bumped by [reset]: an answer of a load or change request that started
     * before the origin changed must not land in the new installation's list.
     */
    private var generation = 0

    /** The ids the open list has shown; null while the list is closed. */
    private var shownIds: MutableSet<String>? = null

    /** The chat the user is looking at; its history entries are read while it is visible. */
    private var visibleSessionId: String? = null

    /**
     * Entries this visibility already tried to mark read, as `<id>@<at>`.
     * A failed `PATCH` reloads the server's list; without this guard the
     * reload would try the same entry again forever while the chat stays
     * visible. Leaving and reopening the chat retries.
     */
    private val attemptedVisibleReads = mutableSetOf<String>()

    /**
     * The session list's unread dot also counts an unread history entry, so
     * opening a chat must mark that chat's entries read — the same rule the
     * notification shade follows by clearing its posts
     * (`NotificationCenter.setVisibleSession`).
     *
     * Entries that arrive while the chat is visible (a delivery or a load) are
     * read at once; ones that arrive after it was left stay unread. Passing
     * `null` (chat closed, app backgrounded, another tab covering it) stops
     * the marking.
     */
    fun onSessionVisible(sessionId: String?) {
        val next = sessionId?.takeIf { it.isNotBlank() }
        if (visibleSessionId != next) {
            visibleSessionId = next
            attemptedVisibleReads.clear()
        }
        // The same chat stays visible: catch entries that landed since the last
        // mark without waiting for another composition.
        markVisibleSessionRead()
    }

    /** Marks the visible chat's unread entries read, if any; a no-op otherwise. */
    private fun markVisibleSessionRead() {
        val sessionId = visibleSessionId ?: return
        val pending = _state.value.entries.filter { entry ->
            !entry.read &&
                entry.event.concerns(sessionId) &&
                "${entry.id}@${entry.at}" !in attemptedVisibleReads
        }
        if (pending.isEmpty()) return
        attemptedVisibleReads += pending.map { "${it.id}@${it.at}" }
        markRead(pending.map { it.id })
    }

    /**
     * `GET /api/notifications/history`. The pane calls it on open and on every
     * resume, which also covers notifications missed while the pane was gone.
     * A delivery is merged locally by [onDelivered] instead of refetching, like
     * the web store.
     */
    fun refresh() {
        val token = sync.beginLoad()
        loadsInFlight++
        val startedIn = generation
        _state.update { it.copy(loading = true) }
        scope.launch {
            val loaded = captureApi { api.load() }
            // The list switched installations while this request ran.
            if (startedIn != generation) return@launch
            loadsInFlight--
            loaded.fold(
                onSuccess = { entries ->
                    when (sync.decide(token)) {
                        SnapshotDecision.Apply -> _state.update {
                            it.copy(entries = entries, loading = loadsInFlight > 0, error = null)
                        }

                        SnapshotDecision.Reload -> {
                            _state.update { it.copy(loading = loadsInFlight > 0) }
                            refresh()
                        }

                        SnapshotDecision.ReloadWhenSettled -> {
                            _state.update { it.copy(loading = loadsInFlight > 0) }
                        }

                        SnapshotDecision.Ignore -> {
                            _state.update { it.copy(loading = loadsInFlight > 0) }
                        }
                    }
                    recordShown()
                    // A load can bring an entry for the chat on screen (the
                    // history was not loaded yet when it was opened).
                    markVisibleSessionRead()
                },
                onFailure = { error ->
                    // Keep what is shown; a stale failure must not replace a newer load's answer.
                    _state.update {
                        it.copy(
                            loading = loadsInFlight > 0,
                            error = if (sync.isLatest(token)) error.userMessage() else it.error,
                        )
                    }
                },
            )
        }
    }

    /**
     * A notification delivered to this installation over the live channel. The
     * server recorded it already; this only avoids waiting for the next load.
     */
    fun onDelivered(event: JsonObject) {
        val entry = notificationHistoryEntryFromEvent(event, System.currentTimeMillis()) ?: return
        sync.localChange()
        _state.update { it.copy(entries = mergeDeliveredNotification(it.entries, entry)) }
        recordShown()
        // The chat on screen is being read: a frame that still names it must
        // not leave a dot or a badge behind.
        markVisibleSessionRead()
    }

    /** Mark exactly [ids] read (optimistic, then `PATCH`). */
    fun markRead(ids: List<String>) {
        val unread = _state.value.entries.filter { !it.read }.map { it.id }.toSet()
        val target = ids.filter { it in unread }.distinct()
        if (target.isEmpty()) return
        change(
            next = { entries -> markNotificationHistoryRead(entries, target) },
            request = { api.markRead(target) },
        )
    }

    /**
     * The toolbar's "Mark all as read": every unread entry the list currently
     * shows (the server caps the list at 100, so the PATCH stays within its limit).
     */
    fun markAllRead() {
        val target = notificationReadIds(_state.value.entries)
        if (target.isEmpty()) return
        change(
            next = { entries -> markNotificationHistoryRead(entries, target) },
            request = { api.markRead(target) },
        )
    }

    /** Swipe/row dismiss: remove first, delete on the server afterwards. */
    fun remove(id: String) {
        change(
            next = { entries -> removeNotificationHistoryEntry(entries, id) },
            request = { api.remove(id) },
        )
    }

    /** "Clear all": empty the list first, clear the server afterwards. */
    fun clearAll() {
        change(
            next = { emptyList() },
            request = { api.clear() },
        )
    }

    /**
     * The list became visible. It records what it shows until [onListClosed],
     * so leaving it marks only those entries read (web `AppShell` layout effect).
     */
    fun onListOpened() {
        shownIds = mutableSetOf()
        recordShown()
    }

    /** The list was closed: mark what it showed, leave later arrivals unread. */
    fun onListClosed() {
        val seen = shownIds ?: return
        shownIds = null
        if (seen.isEmpty()) return
        val unread = _state.value.entries.filter { !it.read && it.id in seen }.map { it.id }
        markRead(unread)
    }

    /** Dismiss the retry banner without touching the list. */
    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /**
     * The user left this installation (sign-out, server switch, 401): drop the
     * whole list — it belongs to the old origin — and make every in-flight
     * answer of that origin irrelevant.
     */
    fun reset() {
        generation++
        loadsInFlight = 0
        shownIds = null
        visibleSessionId = null
        attemptedVisibleReads.clear()
        sync.reset()
        _state.value = NotificationHistoryState()
    }

    private fun change(
        next: (List<NotificationHistoryEntry>) -> List<NotificationHistoryEntry>,
        request: suspend () -> Unit,
    ) {
        val startedIn = generation
        sync.localChange()
        _state.update { it.copy(entries = next(it.entries)) }
        recordShown()
        sync.beginRequest()
        scope.launch {
            val result = captureApi { request() }
            if (startedIn != generation) return@launch
            if (result.isFailure) sync.requestFailed()
            // A failed change request means the shown state is not the server's; reload once idle.
            if (sync.endRequest()) refresh()
        }
    }

    private fun recordShown() {
        val shown = shownIds ?: return
        shown += _state.value.entries.map { it.id }
    }
}
