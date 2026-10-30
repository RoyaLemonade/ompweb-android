package io.github.kahme247.ompweb.ui.composer.dictation

import io.github.kahme247.ompweb.data.SettingsSnapshot
import io.github.kahme247.ompweb.domain.DictationEngine
import io.github.kahme247.ompweb.domain.DictationSegmentSource
import io.github.kahme247.ompweb.domain.DictationState
import io.github.kahme247.ompweb.domain.ServerSttRegistry
import io.github.kahme247.ompweb.domain.ServerSttSupport
import io.github.kahme247.ompweb.domain.SttAfter
import io.github.kahme247.ompweb.domain.dictationLanguageLabel
import io.github.kahme247.ompweb.net.ServerAddress
import io.github.kahme247.ompweb.net.SttApi
import io.github.kahme247.ompweb.net.SttStartOutcome
import io.github.kahme247.ompweb.testutil.FakeOnDeviceStt
import io.github.kahme247.ompweb.testutil.FakeSttApi
import io.github.kahme247.ompweb.testutil.FakeVoiceRecorder
import io.github.kahme247.ompweb.testutil.MemoryDictationStore
import io.github.kahme247.ompweb.testutil.awaitValue
import io.github.kahme247.ompweb.testutil.waitFor
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine selection and the on-device live flow: no recorder and no server job
 * for on-device dictation, live partials into the attached field at the caret,
 * a final transcript on stop, and clear failures.
 */
class DictationViewModelTest {

    private var now = 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val strings = DictationStrings(
        noRecognizer = "no recognizer",
        offline = "offline",
        micUnavailable = "no recorder",
        noSpeech = "no speech",
        languageUnsupported = "unsupported",
        languageMissing = { "missing $it" },
        languageDownloading = { "downloading $it" },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private val speech = FakeOnDeviceStt()
    private val api = CountingSttApi()

    private var text = "Reply with"
    private var caret = text.length

    private fun viewModel(
        settings: SettingsSnapshot,
        api: SttApi = this.api,
        registry: ServerSttRegistry = ServerSttRegistry(),
        recorder: VoiceRecorder = FakeVoiceRecorder(),
        store: MemoryDictationStore = MemoryDictationStore(),
        // The timers of one test can run on a virtual clock instead of wall-clock time.
        vmScope: CoroutineScope = scope,
    ): DictationViewModel {
        text = "Reply with"
        caret = text.length
        return DictationViewModel(
            api = api,
            store = store,
            scope = vmScope,
            strings = strings,
            clock = { now },
            settings = MutableStateFlow(settings),
            onDevice = speech,
            deviceLocale = Locale.GERMANY,
            sttRegistry = registry,
            createRecorder = { recorder },
        ).also { vm ->
            vm.attachLiveField(
                text = { text },
                caret = { caret },
                apply = { next, nextCaret ->
                    text = next
                    caret = nextCaret
                },
            )
        }
    }

    /** The upload reads the file on [Dispatchers.IO]; wait for its answer. */
    private fun awaitFailed(vm: DictationViewModel): DictationState.Failed =
        vm.state.awaitValue("the failure state") { it is DictationState.Failed } as DictationState.Failed

    private fun awaitCalls(api: FakeSttApi, count: Int) {
        waitFor("$count calls, saw ${api.calls}") { api.calls.size >= count }
    }

    @Test
    fun `the next engine follows the setting and the server's STT answer`() {
        val origin = ServerAddress(scheme = "http", host = "127.0.0.1", port = 3000)
        val onDevice = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        assertEquals(DictationEngine.ON_DEVICE, onDevice.nextEngine())

        val autoWithoutServer = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "auto"))
        assertEquals(
            "auto without an origin never records for a server",
            DictationEngine.ON_DEVICE,
            autoWithoutServer.nextEngine(),
        )

        val autoWithServer = viewModel(
            SettingsSnapshot(loaded = true, origin = origin, dictationEngine = "auto"),
        )
        assertEquals(
            "a server that has not answered is not used",
            DictationEngine.ON_DEVICE,
            autoWithServer.nextEngine(),
        )

        val available = ServerSttRegistry().also { it.mark(origin.origin, ServerSttSupport.Available) }
        val autoWithStt = viewModel(
            SettingsSnapshot(loaded = true, origin = origin, dictationEngine = "auto"),
            registry = available,
        )
        assertEquals(DictationEngine.SERVER, autoWithStt.nextEngine())

        val unavailable = ServerSttRegistry().also {
            it.mark(origin.origin, ServerSttSupport.NotConfigured)
        }
        val autoWithoutStt = viewModel(
            SettingsSnapshot(loaded = true, origin = origin, dictationEngine = "auto"),
            registry = unavailable,
        )
        assertEquals(DictationEngine.ON_DEVICE, autoWithoutStt.nextEngine())

        val server = viewModel(
            SettingsSnapshot(loaded = true, origin = origin, dictationEngine = "server"),
            registry = unavailable,
        )
        assertEquals(DictationEngine.SERVER, server.nextEngine())
    }

    @Test
    fun `the picked language reaches the recognizer and falls back to the device`() {
        val picked = viewModel(
            SettingsSnapshot(loaded = true, dictationEngine = "on_device", dictationLanguage = "fr-FR"),
        )
        picked.start(SttAfter.Insert)
        assertEquals("the explicit pick wins", "fr-FR", speech.language)

        val broken = viewModel(
            SettingsSnapshot(loaded = true, dictationEngine = "on_device", dictationLanguage = "not a tag"),
        )
        broken.start(SttAfter.Insert)
        assertEquals("a malformed pick uses the device language", "de-DE", speech.language)
    }

    @Test
    fun `on-device dictation types live partials at the caret without a server job`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))

        vm.start(SttAfter.Insert)
        assertTrue("the live state keeps the field visible", vm.state.value is DictationState.Live)
        assertEquals("the device language is the default recognition language", "de-DE", speech.language)

        speech.listener!!.onPartial("hallo")
        assertEquals("Reply with hallo", text)
        assertEquals(16, caret)

        speech.listener!!.onPartial("hallo du")
        assertEquals("the partial is replaced, not appended", "Reply with hallo du", text)

        speech.listener!!.onFinal("Hallo du.")
        assertEquals("Reply with Hallo du.", text)
        assertTrue(vm.state.value is DictationState.Idle)
        assertEquals("on-device dictation never uploads audio", 0, api.starts)
        assertTrue("the recognizer is released after the transcript", speech.released)
    }

    /**
     * What the Pixel produces for "speech, pause, speech": one session, the
     * utterance before the pause is finalized (a segment on Android 13+, a
     * partial flagged final before that), and the next utterance starts from
     * an empty hypothesis. Both utterances belong to the same take.
     */
    @Test
    fun `speech before and after a pause both stay in the field`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)
        val listener = speech.listener!!

        listener.onPartial("hallo")
        assertEquals("Reply with hallo", text)

        listener.onSegment("hallo", DictationSegmentSource.SegmentResults)
        assertEquals("the finalized utterance stays visible", "Reply with hallo", text)

        listener.onPartial("wie geht es dir")
        assertEquals(
            "the next utterance is appended, not put in place of the first",
            "Reply with hallo wie geht es dir",
            text,
        )

        listener.onFinal("Hallo wie geht es dir.")
        assertEquals("the full transcript replaces the take once", "Reply with Hallo wie geht es dir.", text)
        assertTrue(vm.state.value is DictationState.Idle)
    }

    @Test
    fun `a phrase said again after a pause is kept`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)
        val listener = speech.listener!!

        listener.onSegment("ok", DictationSegmentSource.SegmentResults)
        listener.onSegment("ok weiter", DictationSegmentSource.SegmentResults)
        assertEquals(
            "a repeated phrase is the user's text, not a re-send",
            "Reply with ok ok weiter",
            text,
        )

        listener.onFinal("OK OK weiter geht es.")
        assertEquals("Reply with OK OK weiter geht es.", text)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the stop fallback keeps every utterance when the recognizer never answers`() {
        val scheduler = TestCoroutineScheduler()
        val virtual = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"), vmScope = virtual)
        vm.start(SttAfter.Insert)
        val listener = speech.listener!!

        listener.onPartial("hallo")
        listener.onSegment("hallo", DictationSegmentSource.SegmentResults)
        listener.onPartial("wie geht es dir")

        vm.stop()
        assertEquals(
            "the text stays visible while the answer is pending",
            "Reply with hallo wie geht es dir",
            text,
        )
        scheduler.advanceTimeBy(FALLBACK_TIMEOUT_MS + 100)
        scheduler.runCurrent()
        virtual.cancel()

        assertEquals("both utterances survive the missing answer", "Reply with hallo wie geht es dir", text)
        assertTrue("the take ends on the fallback", vm.state.value is DictationState.Idle)
    }

    @Test
    fun `auto without a configured server uses the phone`() {
        val vm = viewModel(SettingsSnapshot(loaded = true))

        vm.start(SttAfter.Insert)
        assertTrue(vm.state.value is DictationState.Live)
        assertEquals("de-DE", speech.language)
        assertEquals(0, api.starts)
    }

    @Test
    fun `auto probes the server before recording and uploads when it can transcribe`() {
        val origin = ServerAddress(scheme = "http", host = "127.0.0.1", port = 8080)
        val api = FakeSttApi().apply {
            support = ServerSttSupport.Available
            startResult = SttStartOutcome.Accepted("job-1")
        }
        val recorder = FakeVoiceRecorder()
        val vm = viewModel(
            SettingsSnapshot(loaded = true, origin = origin, dictationEngine = "auto"),
            api = api,
            recorder = recorder,
        )

        vm.start(SttAfter.Insert)
        awaitCalls(api, 1)
        assertEquals("the server is asked before the microphone opens", listOf("probe"), api.calls)
        assertTrue("the server path records", recorder.started)
        assertNull("the phone recognizer stays off", speech.listener)
        assertFalse(vm.state.value is DictationState.Live)

        vm.stop()
        awaitCalls(api, 2)
        assertEquals(listOf("probe", "start"), api.calls)
    }

    @Test
    fun `auto keeps the take local when the server has no STT and starts the phone on request`() {
        val origin = ServerAddress(scheme = "http", host = "127.0.0.1", port = 8080)
        val api = FakeSttApi().apply {
            support = ServerSttSupport.Available
            startResult = SttStartOutcome.Rejected("STT not configured", notConfigured = true)
        }
        val registry = ServerSttRegistry()
        val store = MemoryDictationStore()
        val recorder = FakeVoiceRecorder()
        val vm = viewModel(
            SettingsSnapshot(loaded = true, origin = origin, dictationEngine = "auto"),
            api = api,
            registry = registry,
            recorder = recorder,
            store = store,
        )
        vm.bindScope("sessions/a")

        vm.start(SttAfter.Insert)
        waitFor("the recorder starts after the probe") { recorder.started }
        vm.stop()
        val failed = awaitFailed(vm)

        assertEquals(strings.serverNoStt, failed.message)
        assertFalse("no retry uploads the same take to that server", failed.canRetry)
        assertTrue("the deck offers the phone", failed.canDictateOnDevice)
        assertTrue("the take stays playable", vm.canPlay())
        assertEquals("the local copy is kept", "sessions/a", store.keptScope)
        assertEquals("the origin is marked for later takes", ServerSttSupport.NotConfigured, registry.get(origin.origin))
        assertEquals("the next Auto take is on-device", DictationEngine.ON_DEVICE, vm.nextEngine())

        vm.dictateOnDevice()
        assertTrue("the phone recognizer starts", speech.started)
        assertTrue(vm.state.value is DictationState.Live)
        assertNull("the explicit choice releases the unusable take", store.savedRecording("sessions/a"))
    }

    @Test
    fun `an explicit server engine shows the server's message and stays on the server`() {
        val origin = ServerAddress(scheme = "http", host = "127.0.0.1", port = 8080)
        val api = FakeSttApi().apply {
            startResult = SttStartOutcome.Rejected("STT not configured. Set OMP_WEB_STT_ENDPOINT.", notConfigured = true)
        }
        val registry = ServerSttRegistry()
        val recorder = FakeVoiceRecorder()
        val vm = viewModel(
            SettingsSnapshot(loaded = true, origin = origin, dictationEngine = "server"),
            api = api,
            registry = registry,
            recorder = recorder,
        )

        vm.start(SttAfter.Insert)
        assertEquals("an explicit server choice does not probe first", 0, api.calls.size)
        assertTrue("an explicit server choice records", recorder.started)
        vm.stop()
        val failed = awaitFailed(vm)

        assertEquals("STT not configured. Set OMP_WEB_STT_ENDPOINT.", failed.message)
        assertFalse("no on-device offer for a deliberate server choice", failed.canDictateOnDevice)
        assertTrue("the take can be retried", failed.canRetry)
        assertEquals(ServerSttSupport.NotConfigured, registry.get(origin.origin))
        assertEquals("the explicit engine stays", DictationEngine.SERVER, vm.nextEngine())
    }

    @Test
    fun `an explicit server engine ignores the phone recognizer`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "server"))

        vm.start(SttAfter.Insert)
        assertNull(speech.listener)
        assertFalse(vm.state.value is DictationState.Live)
    }

    @Test
    fun `a missing recognizer fails with a clear message`() {
        speech.available = false
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))

        vm.start(SttAfter.Insert)
        val state = vm.state.value as DictationState.Failed
        assertEquals("no recognizer", state.message)
        assertFalse(state.canRetry)
        assertEquals("the field is untouched", "Reply with", text)
        assertFalse("no recognizer is created when no model is installed", speech.started)
        assertNull(speech.listener)
    }

    @Test
    fun `auto without a model keeps the server path instead of a recognizer`() {
        speech.available = false
        val vm = viewModel(
            SettingsSnapshot(
                loaded = true,
                origin = ServerAddress(scheme = "http", host = "127.0.0.1", port = 3000),
                dictationEngine = "auto",
            ),
        )

        assertEquals("a configured server is the fallback", DictationEngine.SERVER, vm.nextEngine())
        vm.start(SttAfter.Insert)
        assertFalse("the network recognizer never runs", speech.started)
        assertNull(speech.listener)
        assertFalse("no live state without a model", vm.state.value is DictationState.Live)
    }

    @Test
    fun `backgrounding commits the live partial and destroys the recognizer`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)
        speech.listener!!.onPartial("hallo")

        vm.setVisible(false)

        assertEquals("what was said becomes draft text", "Reply with hallo", text)
        assertTrue("the live state ends", vm.state.value is DictationState.Idle)
        assertTrue("the recognizer is destroyed on ON_STOP", speech.released)
    }

    @Test
    fun `auto without a server and without a model fails instead of using the network`() {
        speech.available = false
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "auto"))

        vm.start(SttAfter.Insert)
        val state = vm.state.value as DictationState.Failed
        assertEquals("no recognizer", state.message)
        assertEquals("nothing was uploaded", 0, api.starts)
        assertNull("no cloud recognizer was started", speech.listener)
    }

    @Test
    fun `recognition errors keep the spoken text and name the cause`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)
        speech.listener!!.onPartial("hallo")

        speech.listener!!.onError(OnDeviceSttError.Offline)
        assertEquals("what was said stays as draft text", "Reply with hallo", text)
        assertEquals("offline", (vm.state.value as DictationState.Failed).message)
    }

    @Test
    fun `silence without any word reports no speech`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)

        speech.listener!!.onError(OnDeviceSttError.NoSpeech)
        assertEquals("no speech", (vm.state.value as DictationState.Failed).message)
        assertEquals("Reply with", text)
    }

    @Test
    fun `a missing language model names the language instead of claiming silence`() {
        val vm = viewModel(
            SettingsSnapshot(loaded = true, dictationEngine = "on_device", dictationLanguage = "fr-FR"),
        )
        vm.start(SttAfter.Insert)

        speech.listener!!.onError(OnDeviceSttError.LanguageDownloading)

        val state = vm.state.value as DictationState.Failed
        assertEquals("downloading " + dictationLanguageLabel("fr-FR", Locale.GERMANY), state.message)
        assertEquals("the field is untouched", "Reply with", text)
    }

    @Test
    fun `an unsupported language does not read like the user stayed silent`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)

        speech.listener!!.onError(OnDeviceSttError.LanguageUnsupported)

        assertEquals("unsupported", (vm.state.value as DictationState.Failed).message)
    }

    @Test
    fun `a recognizer that stops before it hears anything says so`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)

        speech.listener!!.onError(OnDeviceSttError.StoppedTooEarly)

        assertEquals(strings.recognizerStoppedEarly, (vm.state.value as DictationState.Failed).message)
    }

    @Test
    fun `cancelling removes the live text again`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)
        speech.listener!!.onPartial("hallo")
        assertEquals("Reply with hallo", text)

        vm.cancel()
        assertEquals("Reply with", text)
        assertTrue(vm.state.value is DictationState.Idle)
        assertTrue(speech.cancelled)
    }

    @Test
    fun `switching the composer scope drops live text instead of pasting it elsewhere`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.bindScope("sessions/a")
        vm.start(SttAfter.Insert)
        speech.listener!!.onPartial("geheim")
        assertEquals("Reply with geheim", text)

        // The next session binds its own draft after the composer switched.
        text = ""
        caret = 0
        vm.bindScope("sessions/b")
        assertEquals("the other session's draft stays empty", "", text)
        assertTrue(vm.state.value is DictationState.Idle)
        assertTrue(speech.cancelled)
    }

    @Test
    fun `stopping keeps the partial when the recognizer never answers`() {
        val vm = viewModel(SettingsSnapshot(loaded = true, dictationEngine = "on_device"))
        vm.start(SttAfter.Insert)
        speech.listener!!.onPartial("hallo du")

        vm.stop()
        assertTrue("the recognizer is asked to finish", speech.stopped)
        assertEquals("the text stays visible while the answer is pending", "Reply with hallo du", text)
    }

    /** Counts uploads so the on-device tests can prove the server is untouched. */
    private class CountingSttApi : SttApi by FakeSttApi() {
        var starts = 0

        override suspend fun start(
            scope: String?,
            owner: String,
            after: SttAfter?,
            audio: ByteArray,
            fileName: String,
            mimeType: String,
        ): SttStartOutcome {
            starts++
            return SttStartOutcome.Rejected("unexpected upload")
        }
    }

    private companion object {
        /**
         * How long a stopped on-device recognition may take to answer
         * (`DictationViewModel.LIVE_RESULT_TIMEOUT_MS`, private): the fallback
         * test runs it on a virtual clock.
         */
        const val FALLBACK_TIMEOUT_MS = 2_500L
    }
}
