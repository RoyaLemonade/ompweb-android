package io.github.kahme247.ompweb.ui.composer.dictation

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.data.SettingsSnapshot
import io.github.kahme247.ompweb.domain.DictationEngine
import io.github.kahme247.ompweb.domain.DictationEnginePreference
import io.github.kahme247.ompweb.domain.DictationMachine
import io.github.kahme247.ompweb.domain.DictationPhase
import io.github.kahme247.ompweb.domain.DictationPlayback
import io.github.kahme247.ompweb.domain.DictationResult
import io.github.kahme247.ompweb.domain.DictationState
import io.github.kahme247.ompweb.domain.DictationStep
import io.github.kahme247.ompweb.domain.DictationStore
import io.github.kahme247.ompweb.domain.LiveDictationBuffer
import io.github.kahme247.ompweb.domain.LocalRecording
import io.github.kahme247.ompweb.domain.ServerSttRegistry
import io.github.kahme247.ompweb.domain.ServerSttSupport
import io.github.kahme247.ompweb.domain.SttAfter
import io.github.kahme247.ompweb.domain.TextInsertion
import io.github.kahme247.ompweb.domain.dictationLanguageLabel
import io.github.kahme247.ompweb.domain.dictationLanguageTag
import io.github.kahme247.ompweb.domain.resolveDictationEngine
import io.github.kahme247.ompweb.net.ApiError
import io.github.kahme247.ompweb.net.SttApi
import io.github.kahme247.ompweb.net.SttRetryOutcome
import io.github.kahme247.ompweb.net.SttStartOutcome
import io.github.kahme247.ompweb.net.captureApi
import io.github.kahme247.ompweb.net.userMessage
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The words this flow shows for its own failures (server messages pass
 * through unchanged). Defaults keep the view model usable in tests;
 * production passes [from].
 */
data class DictationStrings(
    val micDenied: String = "Microphone access is required for dictation",
    val micUnavailable: String = "Recording is not available on this device",
    val noSpeech: String = "No speech detected",
    val failed: String = "Transcription failed",
    val timedOut: String = "Transcription timed out",
    val jobMissing: String = "Transcription job not found",
    val busy: String = "Too many transcriptions in progress",
    val uploadInterrupted: String = "Recording not transcribed yet",
    /**
     * Auto learned that the server cannot transcribe; the deck pairs it with
     * the one-tap on-device take ([DictationState.Failed.canDictateOnDevice]).
     */
    val serverNoStt: String =
        "The server cannot transcribe (speech-to-text is not configured). The recording is kept " +
            "on this phone, but on-device dictation cannot reuse it.",
    val noRecognizer: String =
        "Speech recognition on this phone is not available (needs Android 12+ and the offline " +
            "language pack). Use server transcription or install the language pack.",
    val offline: String = "On-device speech recognition could not run. Try again.",
    val languageUnsupported: String =
        "This phone cannot recognize that language on-device. Pick another dictation language.",
    /** [String] is the language's display name, e.g. "German (Germany)". */
    val languageMissing: (String) -> String = { language ->
        "The on-device language pack for $language is not installed and could not be downloaded. " +
            "Install the language data in Android settings, or pick another dictation language."
    },
    val languageDownloading: (String) -> String = { language ->
        "The on-device language pack for $language is not installed yet. Android is downloading " +
            "it — try dictation again in a moment."
    },
    val recognizerBusy: String = "Speech recognition is busy",
    val recognizerNotReady: String = "Speech recognition did not start on this phone. Try again.",
    val recognizerStoppedEarly: String = "Speech recognition stopped before it could hear you. Try again.",
    val recognizerFailed: String = "Speech recognition failed",
) {
    companion object {
        fun from(context: Context): DictationStrings {
            val app = context.applicationContext
            return DictationStrings(
                micDenied = app.getString(R.string.dictation_error_mic_denied),
                micUnavailable = app.getString(R.string.dictation_error_mic_unavailable),
                noSpeech = app.getString(R.string.dictation_error_no_speech),
                failed = app.getString(R.string.dictation_error_failed),
                timedOut = app.getString(R.string.dictation_error_timeout),
                jobMissing = app.getString(R.string.dictation_error_job_missing),
                busy = app.getString(R.string.dictation_error_busy),
                uploadInterrupted = app.getString(R.string.dictation_error_upload_interrupted),
                serverNoStt = app.getString(R.string.dictation_error_server_no_stt),
                noRecognizer = app.getString(R.string.dictation_error_no_recognizer),
                offline = app.getString(R.string.dictation_error_offline),
                languageUnsupported = app.getString(R.string.dictation_error_language),
                languageMissing = { language ->
                    app.getString(R.string.dictation_error_language_missing, language)
                },
                languageDownloading = { language ->
                    app.getString(R.string.dictation_error_language_downloading, language)
                },
                recognizerBusy = app.getString(R.string.dictation_error_recognizer_busy),
                recognizerNotReady = app.getString(R.string.dictation_error_recognizer_not_ready),
                recognizerStoppedEarly = app.getString(R.string.dictation_error_recognizer_stopped_early),
                recognizerFailed = app.getString(R.string.dictation_error_recognizer_failed),
            )
        }

    }
}

/**
 * Dictation for one composer: records with a [VoiceRecorder], hands the audio
 * to the server job of `/api/stt`, follows it, and delivers exactly one
 * transcript through [results]. The ownership/claim/retry/timeout rules live in
 * the pure [DictationMachine]; this class owns the Android recorders, the HTTP
 * calls and the timers.
 *
 * Port of `hooks/useDictation.ts`: poll 500 ms, adopt this scope's newest job
 * every 4 s while visible and idle, claim with a per-instance token (the owner
 * token identifies the app installation), 6-minute follow deadline, retry with
 * the server's kept audio and fall back to the local copy.
 *
 * With "Dictation engine" On-device (or Auto without a server that can
 * transcribe) the recorder and the job protocol are bypassed: Android's
 * [OnDeviceStt] reports the utterances the recognizer finalizes and the
 * hypothesis of the one in progress, and [LiveDictationBuffer] splices them
 * into the composer at the caret. The take ends on a tap or when the composer
 * is backgrounded — a pause between two utterances ends nothing. Auto asks
 * [ServerSttRegistry] before the microphone opens, so a take is never uploaded
 * to a server that cannot transcribe it.
 */
class DictationViewModel(
    private val api: SttApi,
    private val store: DictationStore,
    private val scope: CoroutineScope,
    private val strings: DictationStrings = DictationStrings(),
    /** Monotonic clock for the machine's deadlines; JVM tests replace the Android one. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    /** Settings snapshot: engine choice, language, whether a server is configured. */
    private val settings: StateFlow<SettingsSnapshot>? = null,
    /** The phone's own recognizer; null (JVM tests) keeps on-device dictation unavailable. */
    private val onDevice: OnDeviceStt? = null,
    /** Device locale for the default recognition language. */
    private val deviceLocale: Locale = Locale.getDefault(),
    /**
     * The server's transcription answer, shared by both composers and warmed
     * at app start; a private registry (JVM tests) probes on first use.
     */
    private val sttRegistry: ServerSttRegistry = ServerSttRegistry(),
    /** The platform recorder; JVM tests pass a fake that writes the take. */
    private val createRecorder: () -> VoiceRecorder = { AndroidVoiceRecorder() },
) {

    private val machine = DictationMachine(clock)

    /** Per composer instance: only the first claim token gets the transcript. */
    private val claimToken: String = UUID.randomUUID().toString().replace("-", "")

    private val ownerToken: String = store.ownerToken()

    private val _state = MutableStateFlow<DictationState>(DictationState.Idle)
    val state: StateFlow<DictationState> = _state.asStateFlow()

    private val _playback = MutableStateFlow<DictationPlayback>(DictationPlayback.Idle)
    val playback: StateFlow<DictationPlayback> = _playback.asStateFlow()

    // A buffered channel, not a lossy SharedFlow: the transcript is user data,
    // so it must survive a composition gap between delivery and the composer's
    // collector instead of being dropped.
    private val resultChannel = Channel<DictationResult>(capacity = Channel.BUFFERED)
    val results: Flow<DictationResult> = resultChannel.receiveAsFlow()

    /** Composer scope: a session id or `new:<cwd>`. */
    private var scopeKey: String? = null
    private var visible = true

    private var recorder: VoiceRecorder? = null
    private var recordingFile: File? = null
    private var localRecording: LocalRecording? = null
    /** Downloaded server copy for playback; deleted the moment playback ends. */
    private var playbackFile: File? = null
    private var level = 0f

    private var tickerJob: Job? = null
    private var followJob: Job? = null
    private var adoptJob: Job? = null
    private var retryJob: Job? = null

    /**
     * The upload in flight, if any. Uploads are never aborted (web
     * `uploadRef`): once sent, the server may already have started the job, so
     * its 202 is always read. [UploadToken.discard] ends that job, [detached]
     * leaves it adoptable for this scope.
     */
    private var uploadToken: UploadToken? = null
    private var playbackJob: Job? = null
    private var player: MediaPlayer? = null

    /**
     * Auto's take failed because the server answered 501: the recording stays
     * local, no retry uploads it again, and the deck offers [dictateOnDevice].
     */
    private var serverNoSttFailure = false

    /** An Auto start is asking the server before it opens the microphone. */
    private var resolvingEngine = false
    private var engineJob: Job? = null

    // --- On-device recognition ---------------------------------------------

    private val liveBuffer = LiveDictationBuffer()
    private var liveActive = false
    private var liveStartedAt = 0L
    /** The BCP-47 tag of the running take, for the failure message. */
    private var liveLanguage = ""
    private var liveJob: Job? = null
    /** Safety net when the recognizer never answers a stop/cancel. */
    private var liveStopJob: Job? = null

    /** The composer field that receives the live partials; set by its owner. */
    private var liveFieldText: () -> String = { "" }
    private var liveFieldCaret: () -> Int = { 0 }
    private var liveFieldApply: ((String, Int) -> Unit)? = null

    private class UploadToken {
        @Volatile var discard = false
        @Volatile var detached = false
    }

    /**
     * Connects the composer field that receives live on-device partials. The
     * view model never holds the field itself: it reads text and caret right
     * before each edit and hands the finished text/caret to [apply].
     */
    fun attachLiveField(text: () -> String, caret: () -> Int, apply: (String, Int) -> Unit) {
        liveFieldText = text
        liveFieldCaret = caret
        liveFieldApply = apply
    }

    // --- Screen lifecycle ---------------------------------------------------

    /** Binds the composer scope; another scope's job is left adoptable on the server. */
    fun bindScope(draftKey: String) {
        if (scopeKey == draftKey) return
        scopeKey = draftKey
        detachFromScope()
        machine.reset()
        publish()
        startAdoptLoop()
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        if (visible) {
            startAdoptLoop()
        } else if (liveActive) {
            // A backgrounded composer cannot show the strip; keep what was
            // spoken so far as draft text.
            commitLive(spoken = liveBuffer.text)
        }
    }

    private fun detachFromScope() {
        // The composer already switched to the new draft when bindScope runs,
        // so restoring here would paste the live text into another session's
        // draft. Drop what was not yet committed instead.
        cancelLive(restore = false)
        followJob?.cancel()
        followJob = null
        adoptJob?.cancel()
        adoptJob = null
        retryJob?.cancel()
        retryJob = null
        engineJob?.cancel()
        engineJob = null
        resolvingEngine = false
        serverNoSttFailure = false
        // An upload in flight is not aborted (web): the server may already have
        // started its job, and the job stays adoptable for this scope.
        uploadToken?.detached = true
        uploadToken = null
        tickerJob?.cancel()
        tickerJob = null
        stopRecorder(deleteFile = true)
        stopPlayback()
        localRecording = null
    }

    // --- Capture ------------------------------------------------------------

    fun start(after: SttAfter) {
        if (liveActive) return
        if (resolvingEngine) return
        if (
            machine.phase == DictationPhase.Recording ||
            machine.phase == DictationPhase.Uploading ||
            machine.phase == DictationPhase.Transcribing
        ) {
            return
        }
        // A new recording replaces the job the deck was showing (web: start()).
        machine.jobId?.let { discardRemote(it) }
        retryJob?.cancel()
        retryJob = null
        stopPlayback()
        tickerJob?.cancel()
        stopRecorder(deleteFile = true)
        localRecording = null
        store.clearRecording()
        serverNoSttFailure = false
        val origin = currentOrigin()
        // Auto must know before the microphone opens: a take uploaded to a
        // server without STT cannot be transcribed (501), and its audio cannot
        // be handed to the phone's recognizer afterwards.
        if (currentPreference() == DictationEnginePreference.AUTO &&
            origin != null &&
            sttRegistry.get(origin) == ServerSttSupport.Unknown
        ) {
            resolvingEngine = true
            engineJob = scope.launch {
                withTimeoutOrNull(ENGINE_PROBE_TIMEOUT_MS) {
                    sttRegistry.ensure(origin) { api.serverSupport() }
                }
                resolvingEngine = false
                engineJob = null
                // A scope switch or a backgrounded composer cancels this job
                // or must not open the microphone out of sight.
                if (isActive && visible) beginCapture(after)
            }
            return
        }
        beginCapture(after)
    }

    private fun beginCapture(after: SttAfter) {
        if (nextEngine() == DictationEngine.ON_DEVICE) {
            startOnDevice()
            return
        }
        if (!machine.start(after)) return
        if (!startRecorder()) {
            machine.fail(strings.micUnavailable)
            publish()
            return
        }
        publish()
        startTicker()
    }

    /**
     * Which engine the next capture uses: the setting, resolved against the
     * server's own transcription answer (see [resolveDictationEngine]). Public
     * for the mic rationale, which must not claim the voice goes to the server
     * when the phone transcribes it.
     */
    fun nextEngine(): DictationEngine {
        val snapshot = settings?.value
        return resolveDictationEngine(
            preference = DictationEnginePreference.fromWire(snapshot?.dictationEngine),
            // No settings (JVM tests) assumes a server whose ability is unknown.
            serverConfigured = snapshot == null || snapshot.origin != null,
            serverStt = sttRegistry.get(snapshot?.origin?.origin),
            onDeviceAvailable = onDeviceModelAvailable,
        )
    }

    private fun currentPreference(): DictationEnginePreference =
        DictationEnginePreference.fromWire(settings?.value?.dictationEngine)

    private fun currentOrigin(): String? = settings?.value?.origin?.origin

    /**
     * Cached per view model: the recognizer query is a binder round trip and
     * must not run on every composition of the mic rationale.
     */
    private val onDeviceModelAvailable: Boolean by lazy {
        val speech = onDevice ?: return@lazy false
        runCatching { speech.isOnDeviceAvailable() }.getOrDefault(false)
    }

    /**
     * Whether an installed on-device model can serve dictation right now. The
     * mic rationale must not promise on-device processing when [start] would
     * fail with [DictationStrings.noRecognizer].
     */
    val onDeviceReady: Boolean get() = onDeviceModelAvailable

    private fun startOnDevice() {
        val speech = onDevice
        if (speech == null || !runCatching { speech.isOnDeviceAvailable() }.getOrDefault(false)) {
            machine.reset()
            machine.fail(strings.noRecognizer)
            publish()
            return
        }
        machine.reset()
        liveBuffer.reset()
        liveStartedAt = clock()
        liveActive = true
        publish()
        val language = dictationLanguageTag(settings?.value?.dictationLanguage, deviceLocale)
        liveLanguage = language
        speech.start(language, liveListener)
        startLiveTicker()
    }

    /**
     * Recognizer callbacks: main thread, in order, exactly one final or error.
     * The buffer owns the take's text — the utterances the recognizer already
     * finalized plus the hypothesis it is in right now — so a pause never
     * throws away what was said before it.
     */
    private val liveListener = object : OnDeviceStt.Listener {
        override fun onPartial(text: String) {
            if (!liveActive) return
            applyLive(liveBuffer.onPartial(liveFieldText(), liveFieldCaret(), text))
            publish()
        }

        override fun onSegment(text: String) {
            if (!liveActive) return
            applyLive(liveBuffer.onSegment(liveFieldText(), liveFieldCaret(), text))
            publish()
        }

        override fun onFinal(text: String) {
            if (!liveActive) return
            commitLive(text.trim())
        }

        override fun onError(error: OnDeviceSttError) {
            if (!liveActive) return
            val spoken = liveBuffer.text
            // The language the take ran with, so a model message names it.
            val languageLabel = dictationLanguageLabel(liveLanguage, deviceLocale)
            val message = when (error) {
                OnDeviceSttError.NoRecognizer -> strings.noRecognizer
                OnDeviceSttError.Permission -> strings.micDenied
                OnDeviceSttError.Offline -> strings.offline
                OnDeviceSttError.LanguageUnsupported -> strings.languageUnsupported
                OnDeviceSttError.LanguageMissing -> strings.languageMissing(languageLabel)
                OnDeviceSttError.LanguageDownloading -> strings.languageDownloading(languageLabel)
                OnDeviceSttError.Busy -> strings.recognizerBusy
                OnDeviceSttError.Client -> strings.recognizerNotReady
                OnDeviceSttError.Audio -> strings.micUnavailable
                OnDeviceSttError.NoSpeech -> if (spoken.isEmpty()) strings.noSpeech else null
                OnDeviceSttError.StoppedTooEarly -> strings.recognizerStoppedEarly
                OnDeviceSttError.Failed -> strings.recognizerFailed
            }
            commitLive(spoken = spoken, message = message)
        }
    }

    /**
     * Ends a live capture: [spoken] is the session's transcript (blank when
     * the recognizer answered nothing and the buffer keeps its utterances),
     * then the state is Idle or the [message] failure.
     */
    private fun commitLive(spoken: String, message: String? = null) {
        if (!liveActive) return
        liveActive = false
        val insertion = liveBuffer.onFinal(liveFieldText(), liveFieldCaret(), spoken)
        applyLive(insertion)
        liveBuffer.reset()
        liveJob?.cancel()
        liveJob = null
        liveStopJob?.cancel()
        liveStopJob = null
        onDevice?.release()
        if (message != null) {
            machine.fail(message)
        } else if (spoken.isBlank() && insertion == null) {
            machine.fail(strings.noSpeech)
        }
        publish()
    }

    /** Ends a live capture without keeping anything (scope switch, cancel). */
    private fun cancelLive(restore: Boolean = true) {
        if (!liveActive) return
        liveActive = false
        runCatching { onDevice?.cancel() }
        if (restore) applyLive(liveBuffer.onCancel(liveFieldText()))
        liveBuffer.reset()
        liveJob?.cancel()
        liveJob = null
        liveStopJob?.cancel()
        liveStopJob = null
    }

    private fun applyLive(insertion: TextInsertion?) {
        if (insertion == null) return
        liveFieldApply?.invoke(insertion.text, insertion.caret)
    }

    private fun startLiveTicker() {
        liveJob?.cancel()
        liveJob = scope.launch {
            while (isActive && liveActive) {
                publish()
                delay(CAPTURE_TICK_MS)
            }
        }
    }

    fun pause() {
        if (!machine.pause()) return
        runCatching { recorder?.pause() }
        publish()
    }

    fun resume() {
        if (!machine.resume()) return
        runCatching { recorder?.resume() }
        publish()
    }

    /** Stops the capture and transcribes with the intent chosen at start. */
    fun stop() {
        if (liveActive) {
            // A tap asks the recognizer to end the session; the utterances it
            // already finalized stay visible until its answer arrives.
            if (liveStopJob?.isActive == true) return
            runCatching { onDevice?.stop() }
            liveStopJob = scope.launch {
                delay(LIVE_RESULT_TIMEOUT_MS)
                if (liveActive) commitLive(spoken = liveBuffer.text)
            }
            return
        }
        finish(machine.after)
    }

    /**
     * Stops the capture with an explicit intent, so the composer's Send/Queue
     * button can decide at stop time (web `stopAndFinishDictation`).
     */
    fun finish(after: SttAfter) {
        if (liveActive) {
            // On-device text is always inserted at the caret; the field sends.
            stop()
            return
        }
        if (machine.phase != DictationPhase.Recording) return
        machine.finishRecording(after)
        tickerJob?.cancel()
        tickerJob = null
        val stopped = stopRecorder()
        val file = recordingFile
        recordingFile = null
        publish()
        if (!stopped || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            machine.fail(strings.noSpeech)
            publish()
            return
        }
        val recording = LocalRecording(file, AUDIO_MIME_TYPE, machine.after)
        localRecording = recording
        store.keepRecording(scopeKey.orEmpty(), file, AUDIO_MIME_TYPE, machine.after)
        machine.setLocalAudio(true)
        publish()
        upload(recording, machine.after)
    }

    fun cancel() {
        if (liveActive) {
            cancelLive()
            machine.reset()
            publish()
        }
        val jobId = machine.jobId
        tickerJob?.cancel()
        tickerJob = null
        followJob?.cancel()
        followJob = null
        retryJob?.cancel()
        retryJob = null
        engineJob?.cancel()
        engineJob = null
        resolvingEngine = false
        serverNoSttFailure = false
        // An upload in flight ends its job once the server answers with its id.
        uploadToken?.discard = true
        uploadToken = null
        stopRecorder(deleteFile = true)
        stopPlayback()
        localRecording = null
        store.clearRecording()
        machine.cancelCapture()
        if (jobId != null) discardRemote(jobId)
        publish()
    }

    fun discard() = cancel()

    /**
     * Logout / server switch: stop capture and playback, delete the kept
     * recording and discard any server job this composer still owns.
     */
    fun clearSessionData() = cancel()

    fun onPermissionDenied() {
        machine.fail(strings.micDenied)
        publish()
    }

    // --- Follow -------------------------------------------------------------

    private fun upload(recording: LocalRecording, after: SttAfter) {
        val key = scopeKey
        val origin = currentOrigin()
        val preference = currentPreference()
        val token = UploadToken()
        uploadToken = token
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { recording.file.readBytes() }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                if (uploadToken === token) uploadToken = null
                if (token.discard || token.detached) return@launch
                machine.fail(strings.noSpeech)
                publish()
                return@launch
            }
            val answer = captureApi {
                api.start(
                    scope = key,
                    owner = ownerToken,
                    after = after.takeIf { it != SttAfter.Insert },
                    audio = bytes,
                    fileName = recording.file.name,
                    mimeType = recording.mimeType,
                )
            }
            if (uploadToken === token) uploadToken = null
            // Cancelled during the upload: the server job that just started is
            // discarded, so no other composer picks it up (web `upload.discard`).
            if (token.discard) {
                (answer.getOrNull() as? SttStartOutcome.Accepted)?.let { discardRemote(it.jobId) }
                return@launch
            }
            // Left this scope meanwhile: the server job stays adoptable.
            if (token.detached || scopeKey != key) return@launch
            when (val outcome = answer.getOrNull()) {
                is SttStartOutcome.Accepted -> {
                    machine.uploadAccepted(outcome.jobId)
                    publish()
                    follow(outcome.jobId)
                }
                is SttStartOutcome.Rejected -> {
                    if (outcome.notConfigured) {
                        onServerSttMissing(origin, preference, outcome.message)
                    } else {
                        machine.fail(outcome.message)
                    }
                    publish()
                }
                null -> {
                    machine.fail(answer.exceptionOrNull()?.userMessage() ?: strings.failed)
                    publish()
                }
            }
        }
    }

    /**
     * A real upload answered 501: the origin is marked for every later take
     * (any engine), and Auto keeps the take local with a one-tap on-device
     * alternative instead of failing the same way on every attempt. Explicit
     * Server keeps the server's own message and its retry, as chosen.
     */
    private fun onServerSttMissing(
        origin: String?,
        preference: DictationEnginePreference,
        message: String,
    ) {
        sttRegistry.mark(origin, ServerSttSupport.NotConfigured)
        if (preference == DictationEnginePreference.AUTO) {
            serverNoSttFailure = true
            machine.fail(strings.serverNoStt)
        } else {
            machine.fail(message)
        }
    }

    /**
     * "Dictate on this phone instead" after Auto learned that the server
     * cannot transcribe. The kept take cannot be fed to the recognizer: this
     * starts a fresh on-device capture and drops the unusable audio only now,
     * on the user's explicit choice (until then it stayed playable).
     */
    fun dictateOnDevice() {
        if (!serverNoSttFailure) return
        serverNoSttFailure = false
        stopPlayback()
        localRecording?.file?.delete()
        localRecording = null
        store.clearRecording()
        machine.reset()
        publish()
        startOnDevice()
    }

    private fun follow(jobId: String) {
        followJob?.cancel()
        followJob = scope.launch {
            var claiming = false
            while (isActive) {
                if (machine.phase != DictationPhase.Transcribing || machine.jobId != jobId) return@launch
                if (claiming) {
                    val answer = captureApi { api.claim(jobId, claimToken, ownerToken) }
                    if (!isActive) return@launch
                    if (answer.isFailure) {
                        fatalMessage(answer.exceptionOrNull())?.let { message ->
                            machine.fail(message)
                            publish()
                            return@launch
                        }
                    } else {
                        val step = machine.takeClaim(answer.getOrNull())
                        claiming = machine.awaitingClaim
                        if (settle(step)) return@launch
                    }
                    delay(DictationMachine.STT_POLL_MS)
                    continue
                }
                delay(DictationMachine.STT_POLL_MS)
                if (!isActive) return@launch
                val answer = captureApi { api.job(jobId) }
                if (answer.isFailure) {
                    fatalMessage(answer.exceptionOrNull())?.let { message ->
                        machine.fail(message)
                        publish()
                        return@launch
                    }
                    continue
                }
                val step = machine.observe(answer.getOrNull())
                if (step is DictationStep.Claim) claiming = true
                if (settle(step)) return@launch
            }
        }
    }

    /** Applies a poll/claim decision; true ends the follow loop. */
    private fun settle(step: DictationStep): Boolean = when (step) {
        DictationStep.Wait, DictationStep.Claim -> false
        is DictationStep.Delivered -> {
            localRecording = null
            store.clearRecording()
            stopPlayback()
            publish()
            resultChannel.trySend(DictationResult(step.text, step.after, step.isOwner))
            true
        }
        is DictationStep.Failed -> {
            // Nothing can retry from this failure: the kept recording and its
            // playback copy are dead weight.
            if (!machine.canRetry) deleteLocalAudio()
            publish()
            true
        }
        DictationStep.StandDown -> {
            localRecording = null
            store.clearRecording()
            publish()
            true
        }
    }

    /** Drop the local recording (and its playback copy) once no retry can use it. */
    private fun deleteLocalAudio() {
        stopPlayback()
        localRecording?.file?.delete()
        localRecording = null
        store.clearRecording()
        machine.setLocalAudio(false)
    }

    // --- Adoption -----------------------------------------------------------

    private fun startAdoptLoop() {
        adoptJob?.cancel()
        val key = scopeKey ?: return
        adoptJob = scope.launch {
            while (isActive) {
                if (visible && !liveActive && machine.canAdopt() && scopeKey == key) {
                    val jobs = captureApi { api.jobs(key) }.getOrNull()
                    if (jobs != null && visible && !liveActive && scopeKey == key && machine.canAdopt()) {
                        if (machine.adopt(jobs)) {
                            publish()
                            if (machine.phase == DictationPhase.Transcribing) {
                                machine.jobId?.let { follow(it) }
                            }
                        } else {
                            restoreLocal(key)
                        }
                    }
                }
                delay(DictationMachine.STT_ADOPT_MS)
            }
        }
    }

    /**
     * Nothing on the server for this scope, but a recording of it was kept
     * (the upload died with the process): offer the local re-upload.
     */
    private fun restoreLocal(key: String) {
        val saved = store.savedRecording(key) ?: return
        localRecording = saved
        machine.restoreRecording(saved.after, strings.uploadInterrupted)
        publish()
    }

    // --- Retry --------------------------------------------------------------

    fun retry() {
        if (machine.phase != DictationPhase.Failed || retryJob?.isActive == true) return
        val jobId = machine.serverRetryJobId()
        if (jobId == null) {
            uploadLocalOrFail()
            return
        }
        val key = scopeKey
        retryJob = scope.launch {
            val answer = captureApi { api.retry(jobId, ownerToken) }
            // Cancelled, restarted or the scope left while the request was in
            // flight: the answer is stale (web checks `cancelledRef`/`jobIdRef`).
            if (machine.phase != DictationPhase.Failed || machine.jobId != jobId || scopeKey != key) {
                return@launch
            }
            when (val outcome = answer.getOrNull()) {
                is SttRetryOutcome.Accepted -> {
                    val step = machine.retryAccepted(outcome.job)
                    if (step != null) {
                        settle(step)
                        return@launch
                    }
                    publish()
                    follow(outcome.job.id)
                }
                SttRetryOutcome.Missing -> {
                    machine.retryJobMissing()
                    // A kept local copy can still be re-uploaded even when the
                    // adopted job never set `hasLocalAudio` (process restart).
                    uploadLocalOrFail()
                }
                SttRetryOutcome.Busy -> {
                    machine.retryRejected(strings.busy)
                    publish()
                }
                is SttRetryOutcome.Rejected -> {
                    if (outcome.notConfigured) {
                        sttRegistry.mark(currentOrigin(), ServerSttSupport.NotConfigured)
                        if (currentPreference() == DictationEnginePreference.AUTO) {
                            serverNoSttFailure = true
                            machine.retryRejected(strings.serverNoStt)
                        } else {
                            machine.retryRejected(outcome.message)
                        }
                    } else {
                        machine.retryRejected(outcome.message)
                    }
                    publish()
                }
                null -> {
                    machine.retryRejected(answer.exceptionOrNull()?.userMessage() ?: strings.failed)
                    publish()
                }
            }
        }
    }

    private fun uploadLocalOrFail() {
        val key = scopeKey ?: return
        val recording = localRecording ?: store.savedRecording(key)
        if (recording == null) {
            machine.fail(strings.jobMissing)
            publish()
            return
        }
        localRecording = recording
        machine.setLocalAudio(true)
        if (machine.beginUploadRetry()) {
            publish()
            upload(recording, recording.after)
        }
    }

    // --- Playback -----------------------------------------------------------

    /** A local recording or the server's kept copy exists for playback. */
    fun canPlay(): Boolean = machine.hasLocalAudio || machine.jobId != null

    fun playPause() {
        val state = _playback.value
        if (state is DictationPlayback.Preparing) return
        val existing = player
        if (existing != null) {
            if (state is DictationPlayback.Playing && !state.paused) {
                runCatching { existing.pause() }
                playbackJob?.cancel()
                _playback.value = state.copy(paused = true)
            } else {
                runCatching { existing.start() }
                val duration = state.durationMsOrZero()
                _playback.value = DictationPlayback.Playing(
                    positionMs = state.positionMsOrZero().coerceAtLeast(0),
                    durationMs = duration,
                    paused = false,
                )
                startPlaybackTicker()
            }
            return
        }
        val jobId = machine.jobId
        val local = localRecording
        if (local == null && jobId == null) return
        _playback.value = DictationPlayback.Preparing
        scope.launch {
            val file = local?.file?.takeIf { it.isFile } ?: downloadRecording(jobId)
            if (file == null) {
                _playback.value = DictationPlayback.Idle
                return@launch
            }
            preparePlayer(file, temporary = local == null)
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        val current = player
        player = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
        playbackFile?.delete()
        playbackFile = null
        _playback.value = DictationPlayback.Idle
    }

    private suspend fun downloadRecording(jobId: String?): File? {
        if (jobId == null) return null
        val audio = captureApi { api.audio(jobId) }.getOrNull() ?: return null
        val extension = when (audio.mimeType) {
            "audio/webm" -> "webm"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> "m4a"
        }
        val file = store.scratchFile("job-$jobId.$extension")
        return withContext(Dispatchers.IO) {
            runCatching { file.writeBytes(audio.bytes) }.map { file }.getOrNull()
        }
    }

    private fun preparePlayer(file: File, temporary: Boolean) {
        stopPlayback()
        if (temporary) playbackFile = file
        val created = MediaPlayer()
        player = created
        created.setOnPreparedListener { prepared ->
            if (player !== prepared) return@setOnPreparedListener
            prepared.start()
            _playback.value = DictationPlayback.Playing(0L, prepared.duration.toLong().coerceAtLeast(0L), false)
            startPlaybackTicker()
        }
        created.setOnCompletionListener {
            if (player === created) stopPlayback()
        }
        created.setOnErrorListener { _, _, _ ->
            if (player === created) stopPlayback()
            true
        }
        try {
            created.setDataSource(file.absolutePath)
            created.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    private fun startPlaybackTicker() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            while (isActive) {
                val current = _playback.value as? DictationPlayback.Playing ?: return@launch
                if (current.paused) return@launch
                val position = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
                _playback.value = current.copy(positionMs = position)
                delay(PLAYBACK_TICK_MS)
            }
        }
    }

    // --- Recorder -----------------------------------------------------------

    private fun startRecorder(): Boolean {
        val file = store.scratchFile("rec-${System.currentTimeMillis()}.m4a")
        val created = createRecorder()
        if (!created.start(file)) {
            created.release()
            file.delete()
            recordingFile = null
            return false
        }
        recorder = created
        recordingFile = file
        level = 0f
        return true
    }

    /** Stops and releases the recorder; false when it never produced audio. */
    private fun stopRecorder(deleteFile: Boolean = false): Boolean {
        val current = recorder
        recorder = null
        if (current == null) {
            if (deleteFile) recordingFile?.delete()
            return false
        }
        val captured = current.stop()
        current.release()
        if (deleteFile) {
            recordingFile?.delete()
            recordingFile = null
        }
        return captured
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive && machine.phase == DictationPhase.Recording) {
                val amplitude = runCatching { recorder?.maxAmplitude() ?: 0 }.getOrDefault(0)
                level = if (machine.paused) 0f else (amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
                if (machine.elapsedMs() >= DictationMachine.MAX_RECORDING_MS && !machine.paused) {
                    finish(machine.after)
                    return@launch
                }
                publish()
                delay(CAPTURE_TICK_MS)
            }
        }
    }

    // --- Plumbing -----------------------------------------------------------

    private fun discardRemote(jobId: String) {
        scope.launch { captureApi { api.discard(jobId) } }
    }

    private fun publish() {
        if (liveActive) {
            _state.value = DictationState.Live(
                elapsedMs = (clock() - liveStartedAt).coerceAtLeast(0L),
            )
            return
        }
        _state.value = when (machine.phase) {
            DictationPhase.Idle -> DictationState.Idle
            DictationPhase.Recording -> DictationState.Recording(
                elapsedMs = machine.elapsedMs(),
                level = level,
                paused = machine.paused,
            )
            DictationPhase.Uploading -> DictationState.Uploading(machine.finalElapsedMs)
            DictationPhase.Transcribing -> DictationState.Transcribing(machine.finalElapsedMs)
            DictationPhase.Failed -> DictationState.Failed(
                message = displayMessage(machine.message),
                // A retry would upload the same take to a server that just
                // said it cannot transcribe; the deck offers the phone instead.
                canRetry = machine.canRetry && !serverNoSttFailure,
                canDictateOnDevice = serverNoSttFailure && onDeviceModelAvailable,
            )
            DictationPhase.Gone -> DictationState.Gone
        }
    }

    private fun displayMessage(message: String?): String = when (message) {
        null -> strings.failed
        DictationMachine.TIMED_OUT -> strings.timedOut
        DictationMachine.JOB_NOT_FOUND -> strings.jobMissing
        DictationMachine.TRANSCRIPTION_FAILED -> strings.failed
        DictationMachine.NO_SPEECH -> strings.noSpeech
        else -> message
    }

    /** null = transient (keep polling); a message = give up with it. */
    private fun fatalMessage(error: Throwable?): String? = when (error) {
        null -> null
        is ApiError.Network, is ApiError.ServerMisconfigured, is ApiError.SessionSlow -> null
        is ApiError.ServerResponse ->
            if (error.status == 408 || error.status == 429 || error.status >= 500) null else error.userMessage()
        is ApiError.Http ->
            if (error.code == 408 || error.code == 429 || error.code >= 500) null else error.userMessage()
        else -> error.userMessage()
    }

    private fun DictationPlayback.durationMsOrZero(): Long =
        (this as? DictationPlayback.Playing)?.durationMs ?: 0L

    private fun DictationPlayback.positionMsOrZero(): Long =
        (this as? DictationPlayback.Playing)?.positionMs ?: 0L

    private companion object {
        const val AUDIO_MIME_TYPE = "audio/mp4"
        const val CAPTURE_TICK_MS = 100L
        const val PLAYBACK_TICK_MS = 200L
        const val MAX_AMPLITUDE = 32_767f

        /** How long a stopped on-device recognition may take to answer. */
        const val LIVE_RESULT_TIMEOUT_MS = 2_500L

        /**
         * How long Auto waits for the server's STT answer before it records:
         * a slow probe falls back to the phone (the next tap asks again).
         */
        const val ENGINE_PROBE_TIMEOUT_MS = 4_000L
    }
}
