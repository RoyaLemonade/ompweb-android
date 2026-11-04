package io.github.kahme247.ompweb.ui.chat

import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.kahme247.ompweb.domain.ComposerImage
import io.github.kahme247.ompweb.domain.SlashCommandItem
import io.github.kahme247.ompweb.events.ConnectionState
import io.github.kahme247.ompweb.events.EventChannel
import io.github.kahme247.ompweb.events.EventMux
import io.github.kahme247.ompweb.events.EventStream
import io.github.kahme247.ompweb.events.EventTransport
import io.github.kahme247.ompweb.events.SessionEventSource
import io.github.kahme247.ompweb.net.BashOutputResult
import io.github.kahme247.ompweb.net.BtwApi
import io.github.kahme247.ompweb.net.ChatApi
import io.github.kahme247.ompweb.net.ContextRawResponse
import io.github.kahme247.ompweb.net.CwdValidation
import io.github.kahme247.ompweb.net.EventPostResult
import io.github.kahme247.ompweb.net.ExtensionApi
import io.github.kahme247.ompweb.net.ForkOutcome
import io.github.kahme247.ompweb.net.QueueRestore
import io.github.kahme247.ompweb.net.QueuedMessageRemoval
import io.github.kahme247.ompweb.net.SessionDetail
import io.github.kahme247.ompweb.net.SessionInfo
import io.github.kahme247.ompweb.net.SkillsResponse
import io.github.kahme247.ompweb.net.SubagentCompletion
import io.github.kahme247.ompweb.testutil.FakeChatSettings
import io.github.kahme247.ompweb.testutil.FakeComposerApi
import io.github.kahme247.ompweb.testutil.FakeFilesApi
import io.github.kahme247.ompweb.testutil.FakeSttApi
import io.github.kahme247.ompweb.testutil.MemoryDictationStore
import io.github.kahme247.ompweb.testutil.MemoryDrafts
import io.github.kahme247.ompweb.ui.composer.ComposerStrings
import io.github.kahme247.ompweb.ui.composer.CompletionSound
import io.github.kahme247.ompweb.ui.composer.controls.ComposerControlsViewModel
import io.github.kahme247.ompweb.ui.composer.controls.WordPredictor
import io.github.kahme247.ompweb.ui.composer.dictation.DictationViewModel
import io.github.kahme247.ompweb.ui.hosttools.HostToolsController
import io.github.kahme247.ompweb.ui.panels.btw.BtwViewModel
import io.github.kahme247.ompweb.ui.panels.extension.ExtensionUiController
import io.github.kahme247.ompweb.ui.theme.OmpWebTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The reported jump: with a chat at the bottom of a running answer, a reload
 * "throws me all the way to the top".
 *
 * A new stream epoch (a re-subscribe after the chat left the screen, a server
 * restart, a reconnect) used to empty the engine's transcript. The lazy list
 * has nothing to render then, so Compose drops its first visible item; the
 * reset reload repopulates the list with the reader standing at the top. The
 * engine keeps the committed transcript now (web `useAgentSession-sync` does
 * the same), which is what these tests hold in place: the reload must not blank
 * the chat, and the reader keeps the row they were looking at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class ChatScreenScrollTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val api = EpochApi()
    private val events = EpochEvents()
    private val settings = FakeChatSettings()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        background.cancel()
    }

    @Test
    fun `a stream epoch keeps the reader at the bottom through the reload`() {
        val vm = openChat()
        assertVisible("m199")
        assertNotVisible("m0")

        emit("""{"type":"agent_start","web":{"streamId":"A","sequence":1}}""")
        settle()
        // A history answer on a real wire takes a moment: hold it so the
        // epoch's own state is what the reader would see.
        api.gate = CompletableDeferred()
        emit("""{"type":"connected","web":{"streamId":"B","sequence":1}}""")
        settle()

        assertEquals("the epoch must not blank the transcript", 200, vm.ui.value.rows.size)
        assertVisible("m199")

        api.gate?.complete(Unit)
        settle()
        assertVisible("m199")
        assertNotVisible("m0")
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a stream epoch keeps a reader who jumped to a row`() {
        val vm = openChat()
        emit("""{"type":"agent_start","web":{"streamId":"A","sequence":1}}""")
        settle()
        // The server-side search hand-off: the transcript jumps to the entry
        // and stops following the tail, like a reader who scrolled away.
        composeRule.runOnIdle { vm.scrollToEntry("s1", "e100") }
        settle()
        assertVisible("m100")
        assertNotVisible("m0")

        api.gate = CompletableDeferred()
        emit("""{"type":"connected","web":{"streamId":"B","sequence":1}}""")
        settle()
        assertVisible("m100")

        api.gate?.complete(Unit)
        settle()
        assertVisible("m100")
        assertNotVisible("m0")
        assertNotVisible("m199")
        vm.viewModelScope.cancel()
    }

    // ------------------------------------------------------------------ setup

    private fun openChat(): ChatViewModel {
        val vm = buildViewModel()
        api.body = page(200)
        composeRule.setContent {
            OmpWebTheme {
                ChatScreen(
                    sessionId = "s1",
                    cwd = "/w",
                    viewModel = vm,
                    onForked = {},
                    onError = {},
                    onOpenFile = { _, _ -> },
                    onOpenSession = {},
                )
            }
        }
        settle()
        return vm
    }

    /** One frame through the session channel, like the SSE stream. */
    private fun emit(raw: String) {
        events.frames.tryEmit(Json.parseToJsonElement(raw).jsonObject)
    }

    /**
     * Idle the main looper (the engine's 16 ms coalescer and the history
     * reloads are coroutines on it) and let Compose measure the result.
     */
    private fun settle() {
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private fun assertVisible(text: String) {
        assertTrue(
            "row $text must be on screen",
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun assertNotVisible(text: String) {
        assertTrue(
            "row $text must not be on screen",
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** A transcript of [count] user turns, one row each (no activity to fold). */
    private fun page(count: Int): String {
        val ids = (0 until count).joinToString(",") { "\"e$it\"" }
        val rows = (0 until count).joinToString(",") { index ->
            """{"role":"user","content":[{"type":"text","text":"m$index"}],"timestamp":${1000 + index}}"""
        }
        return """{"sessionId":"s1","mode":"replace","hasMore":false,""" +
            """"context":{"entryIds":[$ids],"messages":[$rows]}}"""
    }

    private fun buildViewModel(): ChatViewModel = ChatViewModel(
        api = api,
        eventMux = events,
        settings = settings,
        speech = EpochSpeech(),
        savedStateHandle = SavedStateHandle(),
        maybeStartedMessage = "maybe started",
        stopUnconfirmedMessage = "stop unconfirmed",
        drafts = MemoryDrafts(),
        composerStrings = ComposerStrings(
            shellSessionFailed = "a",
            liveStateUnknown = "b",
            submitAbandoned = "c",
            foreignSendBlocked = "d",
            foreignCommandBlocked = "e",
            goalUnsupported = "f",
        ),
        controls = ComposerControlsViewModel(FakeComposerApi(), settings, background),
        dictation = DictationViewModel(FakeSttApi(), MemoryDictationStore(), background),
        wordPredictor = WordPredictor(FakeComposerApi(), settings, background),
        completionSound = CompletionSound {},
        btw = BtwViewModel(
            api = object : BtwApi {
                override suspend fun send(sessionId: String, command: JsonObject): JsonElement? = null
            },
            scope = background,
        ),
        extensionUi = ExtensionUiController(
            api = object : ExtensionApi {
                override suspend fun command(sessionId: String, command: JsonObject): JsonElement? = null
            },
            scope = background,
        ),
        hostTools = HostToolsController(
            api = object : ExtensionApi {
                override suspend fun command(sessionId: String, command: JsonObject): JsonElement? = null
            },
            events = EventMux(
                transport = object : EventTransport {
                    override suspend fun open(): EventStream = awaitCancellation()
                    override suspend fun subscribe(
                        connectionId: String,
                        id: String,
                        channel: EventChannel,
                    ): EventPostResult = EventPostResult.Ok
                    override suspend fun unsubscribe(connectionId: String, id: String) = Unit
                },
                scope = background,
            ),
            settings = settings,
            scope = background,
        ),
        files = FakeFilesApi(),
    )

    // ------------------------------------------------------------------ fakes

    private inner class EpochEvents : SessionEventSource {
        override val state = MutableStateFlow(ConnectionState.Connected)
        val frames = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)

        override fun subscribe(channel: EventChannel): Flow<JsonObject> = flow { emitAll(frames) }
    }

    private class EpochSpeech : Speech {
        override val state: StateFlow<SpeechState> = MutableStateFlow(SpeechState())

        override fun prepare() = Unit
        override fun setVoice(name: String?) = Unit
        override fun speak(id: String, text: String) = Unit
        override fun toggle(id: String, text: String) = Unit
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    /** Answers the transcript and holds the reload until the test releases it. */
    private inner class EpochApi : ChatApi {
        var body: String = ""
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun prompt(
            sessionId: String,
            message: String,
            images: List<ComposerImage>,
            streamingBehavior: String?,
            force: Boolean,
        ): JsonElement? = null

        override suspend fun agentState(sessionId: String, systemPrompt: Boolean): JsonObject =
            Json.parseToJsonElement(
                """{"running":false,"state":{"isStreaming":false,"isPromptRunning":false}}""",
            ).jsonObject

        override suspend fun runningSessionIds(): List<String> = emptyList()
        override suspend fun abort(sessionId: String): JsonElement? = null
        override suspend fun abortRetry(sessionId: String): JsonElement? = null
        override suspend fun steer(
            sessionId: String,
            message: String,
            images: List<ComposerImage>,
        ): JsonElement? = null

        override suspend fun followUp(
            sessionId: String,
            message: String,
            images: List<ComposerImage>,
        ): JsonElement? = null

        override suspend fun bash(
            sessionId: String,
            command: String,
            excludeFromContext: Boolean,
        ): JsonElement? = null

        override suspend fun abortBash(sessionId: String): JsonElement? = null
        override suspend fun abortAndRestoreQueue(sessionId: String): QueueRestore = QueueRestore()
        override suspend fun removeQueuedMessage(
            sessionId: String,
            message: String,
            queue: String,
        ): QueuedMessageRemoval = QueuedMessageRemoval(removed = false)

        override suspend fun promoteQueuedMessage(sessionId: String, message: String): Boolean = false
        override suspend fun commands(sessionId: String): List<SlashCommandItem> = emptyList()

        override suspend fun goal(
            sessionId: String,
            op: String,
            objective: String?,
            tokenBudget: Long?,
        ): JsonElement? = null

        override suspend fun compact(sessionId: String, customInstructions: String?): JsonElement? = null
        override suspend fun reload(sessionId: String): JsonElement? = null
        override suspend fun setSessionName(sessionId: String, name: String): JsonElement? = null
        override suspend fun lastAssistantText(sessionId: String): String? = null
        override suspend fun skills(cwd: String): SkillsResponse = SkillsResponse()

        override suspend fun contextSync(
            sessionId: String,
            cursor: String?,
            leafId: String?,
            includePreCompaction: Boolean,
            deferThinking: Boolean,
            deferMedia: Boolean,
        ): JsonObject {
            gate?.await()
            return Json.parseToJsonElement(body).jsonObject
        }

        override suspend fun contextFull(
            sessionId: String,
            leafId: String?,
            includePreCompaction: Boolean,
            deferThinking: Boolean,
            deferMedia: Boolean,
        ): JsonObject = Json.parseToJsonElement(body).jsonObject

        override suspend fun contextFullRaw(
            sessionId: String,
            leafId: String?,
            includePreCompaction: Boolean,
            deferThinking: Boolean,
            deferMedia: Boolean,
        ): String = body

        override suspend fun contextFullConditional(
            sessionId: String,
            leafId: String?,
            includePreCompaction: Boolean,
            deferThinking: Boolean,
            deferMedia: Boolean,
            etag: String?,
        ): ContextRawResponse {
            gate?.await()
            return ContextRawResponse(body, "etag")
        }

        override suspend fun sessionDetail(id: String, meta: Boolean): SessionDetail =
            SessionDetail(
                sessionId = id,
                info = SessionInfo(id = id, name = "chat", cwd = "/w"),
            )

        override suspend fun githubRepo(cwd: String): String? = null
        override suspend fun forkSession(sessionId: String, entryId: String): ForkOutcome =
            ForkOutcome(cancelled = true)

        override suspend fun thinking(sessionId: String, entryId: String, blockIndex: Int): String = ""
        override suspend fun bashOutput(sessionId: String, path: String): BashOutputResult =
            BashOutputResult.Text("")

        override suspend fun bashOutputBytes(sessionId: String, path: String): ByteArray = ByteArray(0)
        override suspend fun subagentCompletion(sessionId: String, subagentId: String): SubagentCompletion =
            SubagentCompletion()

        override suspend fun subagents(sessionId: String): JsonElement? = null
        override suspend fun subagentHistory(sessionId: String): JsonElement? = null
        override suspend fun subagentTranscript(
            sessionId: String,
            subagentId: String,
            sessionFile: String?,
            fromByte: Long,
        ): JsonElement? = null

        override suspend fun subagentTranscriptDisk(
            sessionId: String,
            subagentId: String,
            fromByte: Long,
        ): JsonElement? = null

        override suspend fun validateCwd(cwd: String): CwdValidation = CwdValidation.Valid(cwd, null)
    }
}
