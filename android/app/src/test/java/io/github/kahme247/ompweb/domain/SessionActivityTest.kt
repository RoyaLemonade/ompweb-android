package io.github.kahme247.ompweb.domain

import io.github.kahme247.ompweb.chat.ChatMessage
import io.github.kahme247.ompweb.net.SessionSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Last used" is the newest user/assistant message, not the session file's
 * mtime: omp writes `session_exit` (and background bookkeeping) to the file, so
 * opening a chat used to move its row to "just now". These tests cover the
 * transcript tail, the projection onto `modified`, and the two consumers that
 * read `modified` (the `Age` chip and the project activity order).
 */
class SessionActivityTest {

    private val now = Instant.parse("2026-03-10T12:00:00Z")

    private fun user(at: Long?) = ChatMessage.User(
        key = "u$at",
        timestamp = at,
        entryId = null,
        text = "hi",
    )

    private fun assistant(at: Long?) = ChatMessage.Assistant(
        key = "a$at",
        timestamp = at,
        entryId = null,
        blocks = emptyList(),
    )

    private fun session(id: String, modified: String, cwd: String = "/work/alpha") = SessionSummary(
        id = id,
        cwd = cwd,
        modified = modified,
        messageCount = 1,
        firstMessage = "hi",
    )

    @Test
    fun `the newest user or assistant message is the last activity`() {
        val messages = listOf(
            user(1_000),
            assistant(2_000),
            // Tool traffic belongs to the turn, it is no activity of its own.
            ChatMessage.ToolResult(key = "t", timestamp = 9_000, entryId = null, toolCallId = "c", toolName = "bash", text = ""),
            ChatMessage.Custom(key = "c", timestamp = 9_500, entryId = null, customType = "session_exit", text = ""),
            user(3_000),
        )

        assertEquals(3_000L, lastConversationAt(messages))
    }

    @Test
    fun `a transcript without messages has no activity`() {
        assertNull(lastConversationAt(emptyList()))
        assertNull(lastConversationAt(listOf(ChatMessage.ToolResult(key = "t", timestamp = 5, entryId = null, toolCallId = "c", toolName = "bash", text = ""))))
        assertNull("an unknown message timestamp is not a time", lastConversationAt(listOf(user(null))))
    }

    @Test
    fun `the known message time replaces the file time`() {
        val sessions = listOf(
            session("opened", "2026-03-10T11:59:00Z"),
            session("untouched", "2026-03-08T09:00:00Z"),
        )

        val projected = sessions.withKnownActivity(mapOf("opened" to 1_772_000_000_000L))

        assertEquals("2026-02-25T06:13:20.000Z", projected[0].modified)
        assertEquals("a session without a record keeps the server's time", "2026-03-08T09:00:00Z", projected[1].modified)
        assertEquals("nothing else changes", "opened", projected[0].id)
    }

    @Test
    fun `the server lastMessageAt wins over an older local memo`() {
        // The chat was opened here yesterday; another client wrote a message
        // today, so the server's newest message is the truth for the row.
        val server = session("s1", "2026-03-10T13:00:00Z")
            .copy(lastMessageAt = "2026-03-10T12:00:00Z")

        val projected = listOf(server).withKnownActivity(
            mapOf("s1" to Instant.parse("2026-03-09T00:00:00Z").toEpochMilli()),
        )

        assertEquals("2026-03-10T12:00:00.000Z", projected.single().modified)
    }

    @Test
    fun `a newer local memo is still the display time`() {
        // A live message this device just saw may not be in the list's scan
        // yet; the display takes the later of both.
        val server = session("s1", "2026-03-10T13:00:00Z")
            .copy(lastMessageAt = "2026-03-10T12:00:00Z")
        val memo = Instant.parse("2026-03-10T15:00:00Z").toEpochMilli()

        val projected = listOf(server).withKnownActivity(mapOf("s1" to memo))

        assertEquals(sessionTimestampOf(memo), projected.single().modified)
    }

    @Test
    fun `an empty map keeps the list as it is`() {
        val sessions = listOf(session("a", "2026-03-10T11:59:00Z"))

        assertSame(sessions, sessions.withKnownActivity(emptyMap()))
    }

    @Test
    fun `the projected timestamp keeps the server's shape so it still sorts as a string`() {
        // The sidebar sorts `modified` lexicographically; a form without the
        // millisecond part ("...:26Z") would sort before "...:26.000Z".
        val early = sessionTimestampOf(Instant.parse("2026-03-10T11:00:00Z").toEpochMilli())
        val late = sessionTimestampOf(Instant.parse("2026-03-10T11:00:00.500Z").toEpochMilli())

        assertEquals("2026-03-10T11:00:00.000Z", early)
        assertEquals("2026-03-10T11:00:00.500Z", late)
        assertTrue("the later instant sorts after the earlier one", early < late)
    }

    @Test
    fun `a chat opened today does not pass the 24 hour chip when its last message is a week old`() {
        // omp bumped the file when the chat was opened; the last message is old.
        val opened = session("opened", now.toString())
        val lastMessage = now.minusSeconds(7 * 86_400).toEpochMilli()

        val projected = listOf(opened).withKnownActivity(mapOf("opened" to lastMessage))

        assertFalse(
            sessionPassesFilters(
                projected.single(),
                SessionListFilter(age = SessionAgeFilter.Last24Hours),
                runningIds = emptySet(),
                unreadIds = emptySet(),
                now = now,
            ),
        )
        assertTrue(
            "the untouched server time still passes",
            sessionPassesFilters(
                session("untouched", now.toString()),
                SessionListFilter(age = SessionAgeFilter.Last24Hours),
                runningIds = emptySet(),
                unreadIds = emptySet(),
                now = now,
            ),
        )
    }

    @Test
    fun `the project activity order follows the last message, not the open`() {
        val openedToday = session("opened", now.toString(), cwd = "/work/alpha")
        val reallyActive = session("active", now.minusSeconds(60).toString(), cwd = "/work/beta")
        val lastMessage = now.minusSeconds(7 * 86_400).toEpochMilli()

        val projected = listOf(openedToday, reallyActive).withKnownActivity(mapOf("opened" to lastMessage))
        val activity = projectLastActivity(projected)

        assertEquals(
            now.minusSeconds(60).toEpochMilli(),
            activity[comparableProjectPath("/work/beta")],
        )
        assertEquals(lastMessage, activity[comparableProjectPath("/work/alpha")])
    }
}
