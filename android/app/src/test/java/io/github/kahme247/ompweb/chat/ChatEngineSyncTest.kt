package io.github.kahme247.ompweb.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine regressions from the Phase 5 review: append-page id alignment, the
 * queue watermark and the deferred response check.
 */
class ChatEngineSyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun engine(): ChatEngine = ChatEngine(now = { 1_800_000_000_000L })

    private fun page(
        mode: String,
        baseEntryId: String?,
        hasMore: Boolean = false,
        entryIds: List<String>,
        messages: List<String>,
    ): ContextPage {
        val ids = entryIds.joinToString(",") { "\"$it\"" }
        val base = baseEntryId?.let { "\"$it\"" } ?: "null"
        return ContextSync.parse(
            obj(
                """
                {"mode":"$mode","baseEntryId":$base,
                 "cursor":{"firstEntryId":"${entryIds.first()}","lastEntryId":"${entryIds.last()}"},
                 "hasMore":$hasMore,"sessionId":"s1","leafId":"${entryIds.last()}",
                 "context":{"thinkingLevel":"high","model":{"provider":"commandcode","modelId":"m1"},
                            "entryIds":[$ids],"messages":[${messages.joinToString(",")}]}}
                """.trimIndent(),
            ),
        )
    }

    private fun assistant(text: String, timestamp: Long, stopReason: String? = null): String {
        val reason = stopReason?.let { ",\"stopReason\":\"$it\"" } ?: ""
        return """{"role":"assistant","content":[{"type":"text","text":"$text"}],"timestamp":$timestamp$reason}"""
    }

    private fun user(text: String, timestamp: Long): String =
        """{"role":"user","content":[{"type":"text","text":"$text"}],"timestamp":$timestamp}"""

    private fun toolResult(toolCallId: String, text: String, timestamp: Long): String =
        """{"role":"toolResult","toolCallId":"$toolCallId","toolName":"bash",
            "content":[{"type":"text","text":"$text"}],"timestamp":$timestamp}"""

    private fun ChatEngine.feed(vararg frames: JsonObject) {
        frames.forEach {
            onFrame(it)
            flush()
        }
    }

    @Test
    fun `append page keeps entry ids aligned with a frame-committed row`() {
        val engine = engine()
        engine.setSession("s1")
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("hello", 1), assistant("hi", 2, "stop")),
            ),
        )
        // The frame stream committed the answer but the page has not named it.
        engine.feed(obj("""{"type":"message_end","message":${assistant("streamed", 3, "stop")}}"""))
        assertEquals(3, engine.state.value.messages.size)
        assertEquals(2, engine.state.value.entryIds.size)

        // The append page reports the lost tool result *before* the assistant
        // row the frames already committed.
        engine.applyContextPage(
            page(
                mode = "append",
                baseEntryId = "e2",
                entryIds = listOf("e4", "e5"),
                messages = listOf(toolResult("t1", "done", 4), assistant("streamed", 3, "stop")),
            ),
        )

        val state = engine.state.value
        assertEquals("ids stay index-aligned", state.messages.size, state.entryIds.size)
        assertEquals(
            listOf("e1", "e2", "e5", "e4"),
            state.entryIds,
        )
        assertTrue(state.messages.last() is ChatMessage.ToolResult)
        assertEquals(
            "the assistant row keeps the id of its in-place replacement",
            "e5",
            state.entryIds[2],
        )
    }

    @Test
    fun `a partial queue payload keeps the current queue`() {
        val engine = engine()
        engine.feed(obj("""{"type":"queue_update","steering":["steer"],"followUp":["follow"]}"""))
        val queued = engine.state.value
        assertEquals(listOf("steer"), queued.queue.steering)
        assertEquals(listOf("follow"), queued.queue.followUp)

        engine.feed(obj("""{"type":"queue_update","queuedMessages":{"steering":[]}}"""))

        assertEquals(queued.queue, engine.state.value.queue)
        assertEquals(queued.queueRevision, engine.state.value.queueRevision)
    }

    @Test
    fun `a stale agent-state snapshot does not override a newer queue`() {
        val engine = engine()
        engine.feed(obj("""{"type":"queue_update","steering":["new"],"followUp":[]}"""))
        val revision = engine.state.value.queueRevision
        assertNotEquals(0L, revision)

        engine.applyAgentState(
            obj(
                """
                {"running":true,
                 "state":{"isStreaming":false,"isPromptRunning":true,
                          "queuedMessages":{"steering":["old"],"followUp":[]}}}
                """.trimIndent(),
            ),
            expectedQueueRevision = revision - 1,
        )

        val state = engine.state.value
        assertEquals("the newer queue survives", listOf("new"), state.queue.steering)
        assertTrue("the rest of the snapshot still applies", state.promptRunning)
    }

    @Test
    fun `a stale agent-state snapshot for an older run is dropped`() {
        val engine = engine()
        engine.sendStarted("first", runId = 7)
        engine.applyAgentState(
            obj("""{"running":false,"state":{"isStreaming":false,"isPromptRunning":false}}"""),
            expectedRunId = 6,
        )

        val state = engine.state.value
        assertEquals(7L, state.runId)
        assertTrue("the newer run is still busy", state.busy)
        assertEquals("first run pending", "first", engine.state.value.pendingUser?.text)
    }

    @Test
    fun `a replayed auto compaction frame cannot clear the compacting flag`() {
        val engine = engine()
        engine.feed(
            obj(
                """
                {"type":"auto_compaction_start",
                 "web":{"streamId":"w1","sequence":10}}
                """.trimIndent(),
            ),
        )
        assertTrue(engine.state.value.compacting)

        engine.feed(
            obj(
                """
                {"type":"auto_compaction_end","aborted":false,"skipped":false,
                 "web":{"streamId":"w1","sequence":5}}
                """.trimIndent(),
            ),
        )

        assertTrue("an older compaction frame is fenced", engine.state.value.compacting)
    }

    @Test
    fun `an empty run is not judged until the page arrives`() {
        val engine = engine()
        engine.setSession("s1")
        engine.sendStarted("hello", runId = 1)
        engine.feed(obj("""{"type":"agent_end"}"""))

        assertTrue("no notice while the reload is pending", engine.state.value.notices.isEmpty())

        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("hello", 1), assistant("answer", 2, "stop")),
            ),
        )

        assertTrue("the loaded answer settles the run", engine.state.value.notices.isEmpty())
    }

    // --- the optimistic prompt row ("Sending…") -----------------------------

    private fun custom(customType: String, content: String, details: String, timestamp: Long): String =
        """{"role":"custom","customType":"$customType","content":"${content.replace("\"", "\\\"")}",""" +
            """"details":$details,"timestamp":$timestamp}"""

    /**
     * The commit frame is what normally clears the optimistic row, but a
     * reconnect, a backgrounded app or a lost frame leaves the context page as
     * the only evidence. Its user row then sits behind the whole tool run of
     * the turn, so a match over the page's last four rows never sees it and
     * the chat keeps saying "Sending…" under a prompt that already ran.
     */
    @Test
    fun `a page clears the pending row when the prompt sits behind a long tool run`() {
        val engine = engine()
        engine.setSession("s1")
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("earlier", 1), assistant("ok", 2, "stop")),
            ),
        )
        engine.sendStarted("hello", runId = 1)
        engine.drainEffects()

        engine.applyContextPage(
            page(
                mode = "append",
                baseEntryId = "e2",
                entryIds = listOf("e3", "e4", "e5", "e6", "e7", "e8"),
                messages = listOf(
                    user("hello", 3),
                    assistant("working", 4, "toolUse"),
                    toolResult("t1", "one", 5),
                    assistant("working", 6, "toolUse"),
                    toolResult("t2", "two", 7),
                    assistant("done", 8, "stop"),
                ),
            ),
        )

        assertNull("the prompt is in the transcript", engine.state.value.pendingUser)
    }

    /**
     * omp expands a prompt template (and a skill) before it writes the user
     * entry, so the stored text is not the typed text. The entry the send
     * introduced is the identity that proves it arrived (web
     * `runPreviousEntryIdsRef`).
     */
    @Test
    fun `a page clears the pending row when omp stored an expanded prompt`() {
        val engine = engine()
        engine.setSession("s1")
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("earlier", 1), assistant("ok", 2, "stop")),
            ),
        )
        engine.sendStarted("/plan ship it", runId = 1)

        engine.applyContextPage(
            page(
                mode = "append",
                baseEntryId = "e2",
                entryIds = listOf("e3"),
                messages = listOf(user("Prepare a plan for: ship it", 3)),
            ),
        )

        assertNull("the expanded entry is this send", engine.state.value.pendingUser)
    }

    /**
     * A skill invocation is written as a user-attributed `custom` entry, never
     * as a user entry, so neither the text rule nor the user-row rule can see
     * it (web `matchesNativeSkill`).
     */
    @Test
    fun `an expanded skill clears the pending row`() {
        val engine = engine()
        engine.setSession("s1")
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1"),
                messages = listOf(user("earlier", 1)),
            ),
        )
        engine.sendStarted("/skill:demo do it", runId = 1)

        // The echo arrives as a frame first: the chat must not wait for a page.
        val echo = custom(
            "skill-prompt",
            "User invoked the \"demo\" skill",
            """{"name":"demo","args":"do it","prompt":"/skill:demo do it"}""",
            2,
        )
        engine.feed(obj("""{"type":"message_end","message":$echo}"""))

        assertNull("the skill echo is this send", engine.state.value.pendingUser)
    }

    @Test
    fun `a page without the prompt keeps the pending row`() {
        val engine = engine()
        engine.setSession("s1")
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("earlier", 1), assistant("ok", 2, "stop")),
            ),
        )
        engine.sendStarted("hello", runId = 1)

        // A reload of the same history: no user entry the send introduced.
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("earlier", 1), assistant("ok", 2, "stop")),
            ),
        )

        assertEquals("nothing proves the prompt was committed", "hello", engine.state.value.pendingUser?.text)
    }

    /**
     * A page that only names a previously blank assistant slot must not count
     * as the send's entry: the boundary rule is about user rows, which is what
     * a prompt writes.
     */
    @Test
    fun `a page naming a frame-committed assistant row does not settle the prompt`() {
        val engine = engine()
        engine.setSession("s1")
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("earlier", 1), assistant("ok", 2, "stop")),
            ),
        )
        engine.sendStarted("hello", runId = 1)
        // The answer streamed in before its user echo: a frame-committed row
        // with no id yet.
        engine.feed(obj("""{"type":"message_end","message":${assistant("answer", 3, "stop")}}"""))
        assertEquals("the answer alone proves nothing", "hello", engine.state.value.pendingUser?.text)

        engine.applyContextPage(
            page(
                mode = "append",
                baseEntryId = "e2",
                entryIds = listOf("e3"),
                messages = listOf(assistant("answer", 3, "stop")),
            ),
        )

        assertEquals(
            "an assistant entry is not the prompt's entry",
            "hello",
            engine.state.value.pendingUser?.text,
        )
    }

    @Test
    fun `a connected frame asks for a catch-up without resetting the transcript`() {
        val engine = engine()
        engine.setSession("s1")
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("hello", 1), assistant("hi", 2, "stop")),
            ),
        )
        engine.drainEffects()

        engine.feed(obj("""{"type":"connected"}"""))

        assertEquals(
            listOf(ChatEffect.StreamOpened, ChatEffect.RequestContext(reset = false)),
            engine.drainEffects(),
        )
        assertEquals("the existing rows stay until the page replaces them", 2, engine.state.value.messages.size)
    }

    @Test
    fun `a replayed message_end after reconnect commits the row once`() {
        val engine = engine()
        engine.setSession("s1")
        engine.drainEffects()
        val replay = obj("""{"type":"message_end","message":${assistant("answer", 2, "stop")}}""")

        engine.feed(replay)
        engine.feed(replay)

        assertEquals(1, engine.state.value.messages.size)
        val committed = engine.state.value.messages.single() as ChatMessage.Assistant
        assertEquals("answer", (committed.blocks.single() as AssistantBlock.Text).text)
    }

    @Test
    fun `a page without an answer reports the missing response`() {
        val engine = engine()
        engine.setSession("s1")
        engine.sendStarted("hello", runId = 1)
        engine.feed(obj("""{"type":"agent_end"}"""))
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1"),
                messages = listOf(user("hello", 1)),
            ),
        )

        val notice = engine.state.value.notices.single()
        assertEquals(NoticeKind.Error, notice.kind)
        assertEquals("The model returned no response", notice.message)
    }

    // --- cache-first seeding ------------------------------------------------

    private fun cachePage(vararg texts: String): ContextPage = page(
        mode = "replace",
        baseEntryId = null,
        entryIds = texts.indices.map { "e$it" },
        messages = texts.mapIndexed { index, text -> user(text, index + 1L) },
    )

    @Test
    fun `a cached page seeds an empty transcript and marks it stale`() {
        val engine = engine()
        engine.setSession("s1")

        val seeded = engine.seedCachedHistory(cachePage("cached prompt", "cached answer"))

        assertTrue("an empty engine accepts the cache", seeded)
        val state = engine.state.value
        assertEquals(2, state.messages.size)
        assertTrue("the view is marked as updating", state.historyStale)
        assertEquals("the cached view is not a completed load", false, state.historyLoaded)
    }

    @Test
    fun `a cached page never replaces content that is already there`() {
        val engine = engine()
        engine.setSession("s1")
        engine.feed(obj("""{"type":"message_end","message":${assistant("live answer", 2, "stop")}}"""))

        val seeded = engine.seedCachedHistory(cachePage("cached prompt"))

        assertEquals("a live frame that arrived first wins", false, seeded)
        assertEquals(1, engine.state.value.messages.size)
        assertEquals(false, engine.state.value.historyStale)
    }

    @Test
    fun `a real page replaces the cache and clears the stale marker`() {
        val engine = engine()
        engine.setSession("s1")
        engine.seedCachedHistory(cachePage("cached prompt", "cached answer"))

        engine.applyContextPage(cachePage("fresh prompt"))

        val state = engine.state.value
        assertEquals("the server answer wins over the disk copy", 1, state.messages.size)
        assertEquals(false, state.historyStale)
        assertTrue(state.historyLoaded)
    }

    @Test
    fun `a failed refresh keeps the cached transcript with a retry error`() {
        val engine = engine()
        engine.setSession("s1")
        engine.seedCachedHistory(cachePage("cached prompt"))

        engine.historyRequestFailed("Network unreachable")

        val state = engine.state.value
        assertEquals(1, state.messages.size)
        assertEquals(false, state.historyStale)
        assertTrue("the cached rows count as loaded", state.historyLoaded)
        assertEquals("Network unreachable", state.historyError)
    }

    @Test
    fun `a failed first load without a cache keeps the full-pane error`() {
        val engine = engine()
        engine.setSession("s1")

        engine.historyRequestFailed("Network unreachable")

        val state = engine.state.value
        assertEquals(false, state.historyLoaded)
        assertEquals("Network unreachable", state.historyError)
    }

    /**
     * A reconnect, a re-subscribe after the chat left the screen, or a server
     * restart answers with a fresh `web.streamId`. Only the live half of the
     * stream belongs to that epoch; the committed transcript is the session
     * file's history (web `useAgentSession-sync` resets its sync state, never
     * the messages). Emptying it blanked the chat for the whole reload, and the
     * transcript then lost the reader's scroll position.
     */
    @Test
    fun `a new stream epoch keeps the committed transcript`() {
        val engine = engine()
        engine.setSession("s1")
        engine.drainEffects()
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1", "e2"),
                messages = listOf(user("hello", 1), assistant("hi", 2, "stop")),
            ),
        )
        engine.feed(obj("""{"type":"agent_start","web":{"streamId":"a","sequence":1}}"""))
        engine.feed(
            obj(
                """{"type":"message_start","web":{"streamId":"a","sequence":2},
                    "message":{"role":"assistant","content":[{"type":"text","text":"streaming"}],"timestamp":3}}""",
            ),
        )
        engine.feed(
            obj(
                """{"type":"tool_execution_start","web":{"streamId":"a","sequence":3},
                    "toolCallId":"t1","toolName":"bash"}""",
            ),
        )
        engine.drainEffects()
        assertEquals(1, engine.state.value.liveTools.size)

        engine.feed(obj("""{"type":"connected","web":{"streamId":"b","sequence":1}}"""))

        val state = engine.state.value
        assertEquals("the transcript survives the epoch", 2, state.messages.size)
        assertEquals(listOf("e1", "e2"), state.entryIds)
        assertTrue("the rows still count as loaded", state.historyLoaded)
        assertNull("the old stream's reply is gone", state.streaming)
        assertTrue("the old stream's tools are gone", state.liveTools.isEmpty())
        assertTrue(
            "the epoch still reconciles the transcript",
            engine.drainEffects().any { it is ChatEffect.RequestContext && it.reset },
        )
    }

    @Test
    fun `a failed reload after a stream epoch surfaces a retry error over the kept rows`() {
        val engine = engine()
        engine.setSession("s1")
        engine.drainEffects()
        engine.applyContextPage(
            page(
                mode = "replace",
                baseEntryId = null,
                entryIds = listOf("e1"),
                messages = listOf(user("hello", 1)),
            ),
        )
        engine.feed(obj("""{"type":"agent_start","web":{"streamId":"a","sequence":1}}"""))
        engine.feed(obj("""{"type":"connected","web":{"streamId":"b","sequence":1}}"""))
        engine.drainEffects()

        engine.historyRequestFailed("offline")

        val state = engine.state.value
        assertEquals("the kept rows stay on screen", 1, state.messages.size)
        assertTrue(state.historyLoaded)
        assertEquals("the failure is shown with its retry banner", "offline", state.historyError)
    }

    @Test
    fun `an empty cached page is not a seed`() {
        val engine = engine()
        engine.setSession("s1")
        val empty = ContextSync.parse(
            obj("""{"mode":"replace","sessionId":"s1","context":{"entryIds":[],"messages":[]}}"""),
        )

        val seeded = engine.seedCachedHistory(empty)

        assertEquals(false, seeded)
        assertEquals(false, engine.state.value.historyStale)
    }
}
