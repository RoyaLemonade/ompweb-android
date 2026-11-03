package io.github.kahme247.ompweb.domain

import io.github.kahme247.ompweb.chat.ChatMessage
import io.github.kahme247.ompweb.net.SessionSummary
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * "Last used" of a session, as the workspace shows it.
 *
 * `modified` is the session file's mtime (`lib/omp/session-files.ts` reads
 * `statSync().mtime`), and omp writes that file for reasons that are not a
 * message. Resuming a chat appends a `session_exit` entry (and background
 * bookkeeping such as `model_usage`), so merely opening — or reloading — a chat
 * moved its row to "just now" while the last real message was hours old.
 *
 * Two better sources exist and [withKnownActivity] combines them:
 *
 * - the server's `lastMessageAt` (newest user/assistant message, read from the
 *   session file by the server), which also covers messages written by another
 *   client (the web UI, a terminal);
 * - the newest user/assistant message this device has seen in a loaded
 *   transcript ([lastConversationAt]), recorded by the chat screen.
 *
 * The server value wins over the local memo; the memo is newer only while the
 * server's list has not rescanned the file yet, and the display takes the newer
 * of both. Without `lastMessageAt` (an older server) the memo replaces the file
 * time as before, and a session with neither keeps `modified`.
 */

/**
 * The newest timestamp among the user and assistant messages of [messages], or
 * `null` when the transcript has neither. Tool traffic and custom entries carry
 * timestamps too, but they belong to a turn and are no conversation activity of
 * their own.
 */
fun lastConversationAt(messages: List<ChatMessage>): Long? = messages
    .asSequence()
    .filter { it is ChatMessage.User || it is ChatMessage.Assistant }
    .mapNotNull { it.timestamp }
    .maxOrNull()

/**
 * The server's `modified` shape (`Date#toISOString`: UTC, milliseconds,
 * `2026-11-01T05:59:26.887Z`). The sidebar sorts `modified` as a string, so a
 * projected value has to sort chronologically against the server's own values —
 * which a plain `Instant#toString` does not (it drops the fraction at .000).
 */
fun sessionTimestampOf(at: Long): String =
    SESSION_TIMESTAMP.format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))

/**
 * [this] list with `modified` replaced by the newest message this device has
 * seen for a session (`known`, epoch millis by session id).
 *
 * Applying the same value to every consumer is the point: the row's relative
 * time, the `Age` chip, the fork-tree order and the project activity order all
 * read `modified`, so one projection keeps them in agreement. A session without
 * a record keeps the server's file time.
 */
fun List<SessionSummary>.withKnownActivity(known: Map<String, Long>): List<SessionSummary> {
    if (isEmpty()) return this
    var replaced = false
    val projected = map { session ->
        // The server's newest message is the better answer than the file time;
        // a local memo can still be newer for a message the list has not
        // rescanned yet, so the display takes the later of both.
        val serverAt = session.lastMessageAt?.let { parseSessionTimestamp(it)?.toEpochMilli() }
        val knownAt = known[session.id]
        // The later of both; a null side drops out.
        val at: Long? = listOfNotNull(serverAt, knownAt).maxOrNull()
        if (at == null || at <= 0) {
            session
        } else {
            replaced = true
            session.copy(modified = sessionTimestampOf(at))
        }
    }
    return if (replaced) projected else this
}

private val SESSION_TIMESTAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
