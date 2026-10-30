package io.github.kahme247.ompweb.domain

import java.io.File
import java.util.Locale

/** Where a dictation capture is transcribed: this phone or the configured server. */
enum class DictationEngine(val wire: String) {
    SERVER("server"),
    ON_DEVICE("on_device"),
}

/** The user's "Dictation engine" choice (`auto` prefers the server). */
enum class DictationEnginePreference(val wire: String) {
    /** Server STT when the app is connected to one, otherwise this phone. */
    AUTO("auto"),
    ON_DEVICE("on_device"),
    SERVER("server"),
    ;

    companion object {
        fun fromWire(value: String?): DictationEnginePreference =
            entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/**
 * Resolves the preference against the installation.
 *
 * Auto uploads to the server only when that server said it can transcribe
 * ([ServerSttSupport.Available]). A configured server origin alone proves
 * nothing: a stock ompweb answers `/api/stt` with 501 until
 * `OMP_WEB_STT_ENDPOINT`/`OMP_WEB_STT_MODEL` is set and has no capability flag
 * for it, so [ServerSttRegistry] probes the route before recording starts.
 *
 * [onDeviceAvailable] is the phone's installed on-device speech model (not a
 * cloud recognizer). Without a positive server answer Auto uses the phone; a
 * phone without a model is still chosen when the server said it cannot
 * transcribe (the start then explains the missing model — Auto never uploads
 * a take to a server that cannot transcribe it). Only a server whose answer is
 * still [ServerSttSupport.Unknown] stays the last resort for a phone without a
 * model, because the upload then reports the real problem (offline, signed
 * out) instead of a misleading missing-model message.
 *
 * An explicit [DictationEnginePreference.SERVER] always uploads and an explicit
 * [DictationEnginePreference.ON_DEVICE] always fails with the no-model error
 * instead of silently uploading audio.
 */
fun resolveDictationEngine(
    preference: DictationEnginePreference,
    serverConfigured: Boolean,
    serverStt: ServerSttSupport = ServerSttSupport.Unknown,
    onDeviceAvailable: Boolean = true,
): DictationEngine = when (preference) {
    DictationEnginePreference.SERVER -> DictationEngine.SERVER
    DictationEnginePreference.ON_DEVICE -> DictationEngine.ON_DEVICE
    DictationEnginePreference.AUTO -> when {
        serverConfigured && serverStt == ServerSttSupport.Available -> DictationEngine.SERVER
        onDeviceAvailable -> DictationEngine.ON_DEVICE
        // No local model: the server is the only engine left. A server that
        // said it cannot transcribe is not tried (its start reports the
        // missing model instead); an unknown one may just have been asked
        // while offline, so its upload reports the real failure.
        serverConfigured && serverStt == ServerSttSupport.Unknown -> DictationEngine.SERVER
        else -> DictationEngine.ON_DEVICE
    }
}

/** Languages offered in Settings → Dictation language (BCP-47 tags). */
val DICTATION_LANGUAGE_TAGS: List<String> = listOf(
    "en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-BR", "nl-NL",
    "pl-PL", "ru-RU", "tr-TR", "uk-UA", "cs-CZ", "sv-SE", "da-DK", "nb-NO",
    "fi-FI", "el-GR", "hu-HU", "ro-RO", "hi-IN", "id-ID", "ja-JP", "ko-KR",
    "zh-CN", "zh-TW", "th-TH", "vi-VN",
)

private val LANGUAGE_TAG = Regex("^[a-z]{2,3}(-[A-Za-z]{4})?(-([A-Z]{2}|[0-9]{3}))?$")

/**
 * The recognition language: the picked tag when it is a well-formed BCP-47
 * language tag, otherwise the device locale. A blank country stays
 * language-only (`de`), never `de-`.
 */
fun dictationLanguageTag(preferred: String?, device: Locale): String {
    val candidate = preferred?.trim()?.takeIf { it.isNotEmpty() && LANGUAGE_TAG.matches(it) }
    if (candidate != null) return candidate
    val language = device.language.ifBlank { "en" }
    return if (device.country.isBlank()) language else "$language-${device.country.uppercase(Locale.ROOT)}"
}

/** Display name of a language tag in the user's locale ("German (Germany)"). */
fun dictationLanguageLabel(tag: String, display: Locale = Locale.getDefault()): String {
    val name = Locale.forLanguageTag(tag).getDisplayName(display)
    return name.ifBlank { tag }
}

/** The offered tags with the device language first when the list lacks it. */
fun offeredDictationLanguages(
    device: Locale,
    offered: List<String> = DICTATION_LANGUAGE_TAGS,
): List<String> {
    val deviceTag = dictationLanguageTag(null, device)
    return if (offered.any { it.equals(deviceTag, ignoreCase = true) }) offered else listOf(deviceTag) + offered
}

/**
 * Which recognizer callback finalized an utterance. A service can report the
 * same utterance on both paths — `onSegmentResults` and the `final_result`
 * partial that ends it — so the take has to know the path to add it once
 * without mistaking a repeated phrase for that re-send.
 */
enum class DictationSegmentSource {

    /** API 33+ `RecognitionListener.onSegmentResults`. */
    SegmentResults,

    /**
     * The `final_result` partial that finalizes an utterance on services
     * without a segmented session (Chromium's `SpeechRecognitionImpl` path).
     */
    FinalPartial,
}

/**
 * The live on-device transcript as edits on the composer field.
 *
 * One take is not one utterance. Android's recognizer (the Google on-device
 * service on a Pixel) keeps the session open and finalizes an utterance every
 * time the speaker pauses; the next utterance starts from an empty hypothesis.
 * A partial is only the hypothesis of the utterance the recognizer is in right
 * now (`RecognitionListener.onPartialResults`: "might be called at any time",
 * no promise that it grows), so replacing the visible take with the next
 * partial dropped everything said before the pause — the reported "I talk,
 * pause, talk again, and the first text is gone".
 *
 * [onSegment] therefore commits a finalized utterance to the take, and the
 * hypothesis after it is rendered behind the committed text.
 *
 * A commitment is never compared word by word against what the take already
 * holds: the speaker repeating "ok", "ok weiter" at the next pause is text the
 * user said, not a re-send of the committed "ok". The only re-send the buffer
 * drops is the same utterance delivered on both recognizer paths
 * ([DictationSegmentSource]) — a `final_result` partial for an utterance that
 * `onSegmentResults` already reported — and it recognizes that by the
 * delivery path, not by matching words, so a phrase repeated at the next
 * pause survives.
 *
 * The final transcript of the session contains everything the take said
 * (Android: "results for the full speech since onReadyForSpeech"), so it
 * replaces the take instead of being appended — a final that merely repeats
 * the last utterance is not duplicated.
 *
 * The first edit anchors at the caret. The field stays editable while
 * listening: when the user changed it under the live text, the buffer keeps
 * what is visible, treats it as the new base and continues at the new caret.
 * Pure and anchored on text/caret reads, so the composer owns the field.
 */
class LiveDictationBuffer {

    private var base: String? = null
    private var anchor = 0

    /** The hypothesis of the utterance the recognizer is in right now. */
    private var live = ""

    /** The utterances this take already finalized, already joined. */
    private var committed = ""

    /** The text this buffer produced last, to notice a user edit. */
    private var applied: String? = null

    /** The words of the last committed utterance, to recognize its re-send. */
    private var lastSegment: List<String> = emptyList()

    /** The path that delivered [lastSegment] (see [DictationSegmentSource]). */
    private var lastSegmentSource: DictationSegmentSource? = null

    /**
     * True while [lastSegment] is still an utterance the recognizer may report
     * again on the other path; any hypothesis or new utterance ends that.
     */
    private var segmentPending = false

    val active: Boolean get() = base != null

    /** Everything the take produced: the finalized utterances plus the hypothesis. */
    val text: String get() = joined()

    /** A new hypothesis of the current utterance; null when the field is unchanged. */
    fun onPartial(current: String, caret: Int, partial: String): TextInsertion? {
        anchor(current, caret)
        segmentPending = false
        val next = partial.trim()
        if (next == live) return null
        live = next
        return show()
    }

    /**
     * An utterance the recognizer finalized at a pause; null when there is
     * nothing to show. [source] names the callback that delivered it: the same
     * utterance arriving on both paths is added once, everything else — a
     * repeat of earlier words included — is the user's text.
     */
    fun onSegment(
        current: String,
        caret: Int,
        segment: String,
        source: DictationSegmentSource,
    ): TextInsertion? {
        anchor(current, caret)
        val next = segment.trim()
        if (next.isEmpty()) {
            segmentPending = false
        } else {
            val said = words(next)
            val resent = segmentPending && source != lastSegmentSource && sameWords(said, lastSegment)
            if (resent) {
                segmentPending = false
            } else {
                committed = joined(committed, next)
                lastSegment = said
                lastSegmentSource = source
                segmentPending = true
            }
        }
        live = ""
        return show()
    }

    /** The final transcript; null when it is blank and nothing was shown. */
    fun onFinal(current: String, caret: Int, final: String): TextInsertion? {
        val start = base
        val text = final.trim()
        val insertion = if (start == null) {
            if (text.isEmpty()) null else render(current, caret.coerceIn(0, current.length), text)
        } else {
            anchor(current, caret)
            val shown = when {
                // Nothing came back: keep every utterance the take finalized.
                text.isEmpty() -> joined()
                // The session's transcript already contains them.
                repeatsCommitted(text) -> text
                // Only the last utterance: the earlier ones must survive.
                else -> joined(committed, text)
            }
            if (shown.isEmpty()) null else render(base.orEmpty(), anchor, shown)
        }
        reset()
        return insertion
    }

    /** Removes the live text again; null when the user already changed it. */
    fun onCancel(current: String): TextInsertion? {
        val start = base
        val restored = if (start != null && current == applied) TextInsertion(start, anchor) else null
        reset()
        return restored
    }

    fun reset() {
        base = null
        anchor = 0
        live = ""
        committed = ""
        applied = null
        lastSegment = emptyList()
        lastSegmentSource = null
        segmentPending = false
    }

    /**
     * Anchors the take at the field, or re-anchors it after the user edited the
     * field: what is visible is theirs, so it becomes the new base and the
     * text shown so far is part of it (the take continues at the new caret).
     */
    private fun anchor(current: String, caret: Int) {
        if (base == null) {
            base = current
            anchor = caret.coerceIn(0, current.length)
            return
        }
        if (current == applied) return
        base = current
        anchor = caret.coerceIn(0, current.length)
        live = ""
        committed = ""
        lastSegment = emptyList()
        lastSegmentSource = null
        segmentPending = false
    }

    /** Whether [text] starts with the words the take already committed. */
    private fun repeatsCommitted(text: String): Boolean {
        if (committed.isEmpty()) return true
        val kept = words(committed)
        val said = words(text)
        return said.size >= kept.size && kept.indices.all { sameWord(kept[it], said[it]) }
    }

    /** Whether two utterances are the same words (punctuation and case aside). */
    private fun sameWords(first: List<String>, second: List<String>): Boolean =
        first.size == second.size && first.indices.all { sameWord(first[it], second[it]) }

    /** Words compare without punctuation and case: a segment may end in a period. */
    private fun sameWord(kept: String, said: String): Boolean =
        kept.trim(::isNotWordCharacter).equals(said.trim(::isNotWordCharacter), ignoreCase = true)

    private fun isNotWordCharacter(character: Char): Boolean = !character.isLetterOrDigit()

    private fun words(text: String): List<String> =
        text.split(' ', '\n', '\t').filter { it.isNotEmpty() }

    private fun joined(): String = joined(committed, live)

    private fun joined(first: String, second: String): String = when {
        first.isEmpty() -> second
        second.isEmpty() -> first
        else -> "$first $second"
    }

    /** Hands the take's text to the field; null when the field already shows it. */
    private fun show(): TextInsertion? {
        val insertion = render(base.orEmpty(), anchor, joined()) ?: return null
        applied = insertion.text
        return insertion
    }

    private fun render(base: String, anchor: Int, text: String): TextInsertion? {
        val at = anchor.coerceIn(0, base.length)
        val separator = if (text.isNotEmpty() && at > 0 && !base[at - 1].isWhitespace()) " " else ""
        val rendered = base.substring(0, at) + separator + text + base.substring(at)
        if (rendered == base && text.isEmpty()) return null
        return TextInsertion(rendered, at + separator.length + text.length)
    }
}

/**
 * What the composer does with a transcript once the server job finishes.
 * Mirrors `SttAfter` in `lib/stt.ts`: it travels with the recording, is kept
 * on the server job, and comes back with the owner's claim — so the choice
 * survives the composer being rebuilt (session switch, process death).
 */
enum class SttAfter(val wire: String?) {
    Send("send"),
    Steer("steer"),
    FollowUp("followup"),

    /** Record and insert the text into the draft; the field sends it. */
    Insert(null),
}

/** Job lifecycle as `lib/stt-jobs.ts` reports it. */
enum class SttJobStatus(val wire: String) {
    Pending("pending"),
    Done("done"),
    Error("error"),

    /** Another composer claimed or discarded the job; stand down silently. */
    Gone("gone"),
}

/** What a poll of `GET /api/stt/{jobId}` sees (never the transcript). */
data class SttJob(val id: String, val status: SttJobStatus, val error: String?)

/** What `DELETE /api/stt/{jobId}?claim=…` answers; [text] only for the claimer. */
data class SttClaim(
    val id: String,
    val status: SttJobStatus,
    val error: String?,
    val text: String?,
    val after: SttAfter?,
)

/** The recording this app keeps so a lost server job can be re-uploaded. */
data class LocalRecording(val file: File, val mimeType: String, val after: SttAfter)

/**
 * Persistence the dictation flow needs, separated from Android so the
 * ownership/claim logic stays JVM-testable.
 */
interface DictationStore {

    /**
     * This installation's job-owner token (web: `omp-web:stt-owner-token` in
     * sessionStorage). It owns the jobs this app uploads and retries, gets the
     * first claim and alone gets the send/queue choice back. A claim token is
     * per view-model instance and never equals this one.
     */
    fun ownerToken(): String

    /** A fresh file in the app cache for one recording. */
    fun scratchFile(name: String): File

    /** Keeps [file] as the local copy for [scope] (re-upload after a server restart). */
    fun keepRecording(scope: String, file: File, mimeType: String, after: SttAfter)

    /** The kept recording of [scope], if it still exists. */
    fun savedRecording(scope: String): LocalRecording?

    fun clearRecording()
}

/** Everything the composer needs to render the dictation surface. */
sealed interface DictationState {
    data object Idle : DictationState

    data class Recording(
        val elapsedMs: Long,
        val level: Float,
        val paused: Boolean,
    ) : DictationState

    /**
     * On-device recognition is listening: the composer field stays visible and
     * receives the transcript at its caret as it is spoken; the strip below it
     * offers stop/cancel. Pauses between utterances do not end the take, so it
     * stops on a tap (or when the composer is backgrounded).
     */
    data class Live(val elapsedMs: Long) : DictationState

    data class Uploading(val elapsedMs: Long) : DictationState

    data class Transcribing(val elapsedMs: Long) : DictationState

    /**
     * A capture failed. [canDictateOnDevice] marks the case where Auto learned
     * that the server cannot transcribe: the deck offers a one-tap on-device
     * take instead (the failed recording stays local for playback).
     */
    data class Failed(
        val message: String,
        val canRetry: Boolean,
        val canDictateOnDevice: Boolean = false,
    ) : DictationState

    /** The job was claimed or discarded elsewhere; the composer stands down. */
    data object Gone : DictationState
}

/**
 * One finished transcript. [isOwner] is true when this composer uploaded (or
 * retried) the job: only then may the composer act on [after]; another
 * device's transcript is inserted as text only.
 */
data class DictationResult(val text: String, val after: SttAfter, val isOwner: Boolean)

/** What a delivered transcript does with the composer (web `onTranscript`). */
sealed interface DictationOutcome {
    val text: String

    /** Put the transcript into the field; the user sends it. */
    data class Insert(override val text: String) : DictationOutcome

    data class Send(override val text: String) : DictationOutcome

    data class Steer(override val text: String) : DictationOutcome

    data class FollowUp(override val text: String) : DictationOutcome
}

/**
 * Append a transcript to the draft with one separating space, unless the
 * draft is empty or already ends in whitespace (web `onTranscript`).
 */
fun mergeTranscript(draft: String, transcript: String): String =
    if (draft.isNotEmpty() && !draft.endsWith(" ")) "$draft $transcript" else draft + transcript

/**
 * Classifies a finished transcript like the web composer: another composer's
 * job and an explicit `Insert` only fill the field. Without a session (a new
 * chat / the new-session pane) `Send`, `Steer` and `FollowUp` all become a
 * normal send — there is no run to steer and nothing to queue behind.
 */
fun dictationOutcome(
    result: DictationResult,
    draft: String,
    hasSession: Boolean,
): DictationOutcome {
    if (!result.isOwner || result.after == SttAfter.Insert) return DictationOutcome.Insert(result.text)
    val text = mergeTranscript(draft, result.text)
    if (!hasSession) return DictationOutcome.Send(text)
    return when (result.after) {
        SttAfter.Send -> DictationOutcome.Send(text)
        SttAfter.Steer -> DictationOutcome.Steer(text)
        SttAfter.FollowUp -> DictationOutcome.FollowUp(text)
        SttAfter.Insert -> DictationOutcome.Insert(result.text)
    }
}

/** Playback of a recording (local or the server's kept copy). */
sealed interface DictationPlayback {
    data object Idle : DictationPlayback

    data object Preparing : DictationPlayback

    data class Playing(val positionMs: Long, val durationMs: Long, val paused: Boolean = false) : DictationPlayback
}

enum class DictationPhase { Idle, Recording, Uploading, Transcribing, Failed, Gone }

/** One decision of the poll/claim loop (see [DictationMachine]). */
sealed interface DictationStep {
    /** Keep waiting (poll again, or claim again). */
    data object Wait : DictationStep

    /** The job is done: send the one claim that delivers the text. */
    data object Claim : DictationStep

    data class Delivered(val text: String, val after: SttAfter, val isOwner: Boolean) : DictationStep

    data class Failed(val message: String) : DictationStep

    /** Claimed or discarded elsewhere: stop following without an error. */
    data object StandDown : DictationStep
}

/**
 * The dictation ownership/claim/retry/timeout rules of `hooks/useDictation.ts`,
 * as a pure state machine.
 *
 * It owns no I/O: the view model feeds recorder events, HTTP answers and the
 * wall clock in, and acts on the [DictationStep] it gets back. The rules it
 * keeps are the ones the server enforces (`lib/stt-jobs.ts`): a claim on an
 * unfinished job changes nothing, a finished job answers the first claim token
 * (repeatable with the same token), only the owner gets the `after` choice,
 * "gone" means another composer won, a 404 is only this browser's failure when
 * it recorded the job, and the follow loop gives up after six minutes.
 */
class DictationMachine(private val clock: () -> Long) {

    var phase: DictationPhase = DictationPhase.Idle
        private set

    var after: SttAfter = SttAfter.Insert
        private set

    var jobId: String? = null
        private set

    /** True when this composer uploaded or retried [jobId] (first claim, `after`). */
    var mine: Boolean = true
        private set

    var message: String? = null
        private set

    var canRetry: Boolean = false
        private set

    var hasLocalAudio: Boolean = false
        private set

    var paused: Boolean = false
        private set

    /** Length of the finished recording; shown while uploading/transcribing. */
    var finalElapsedMs: Long = 0L
        private set

    private var startedAt = 0L
    private var pausedAt: Long? = null
    private var pausedTotal = 0L
    private var deadline = 0L
    private var claiming = false

    /** True while the last claim answered "done" without text (owner grace). */
    val awaitingClaim: Boolean get() = claiming

    fun reset() {
        phase = DictationPhase.Idle
        after = SttAfter.Insert
        jobId = null
        mine = true
        message = null
        canRetry = false
        hasLocalAudio = false
        paused = false
        finalElapsedMs = 0L
        startedAt = 0L
        pausedAt = null
        pausedTotal = 0L
        deadline = 0L
        claiming = false
    }

    fun setLocalAudio(present: Boolean) {
        hasLocalAudio = present
        canRetry = present || jobId != null
    }

    /** A new recording replaces whatever the deck showed. */
    fun start(after: SttAfter): Boolean {
        if (phase == DictationPhase.Recording || phase == DictationPhase.Uploading || phase == DictationPhase.Transcribing) {
            return false
        }
        reset()
        this.after = after
        phase = DictationPhase.Recording
        startedAt = clock()
        return true
    }

    fun pause(): Boolean {
        if (phase != DictationPhase.Recording || paused) return false
        paused = true
        pausedAt = clock()
        return true
    }

    fun resume(): Boolean {
        if (phase != DictationPhase.Recording || !paused) return false
        pausedTotal += clock() - (pausedAt ?: clock())
        pausedAt = null
        paused = false
        return true
    }

    /** Recording time without the paused stretches, capped at the 5-minute limit. */
    fun elapsedMs(): Long {
        if (phase != DictationPhase.Recording) return finalElapsedMs
        val end = if (paused) (pausedAt ?: clock()) else clock()
        return (end - startedAt - pausedTotal).coerceIn(0L, MAX_RECORDING_MS)
    }

    /**
     * The recorder answered or was stopped: the recording now uploads.
     * [after] lets the composer's Send/Queue button decide the intent at stop
     * time (web `stopAndFinishDictation`); null keeps the intent chosen at
     * [start].
     */
    fun finishRecording(after: SttAfter? = null) {
        if (phase != DictationPhase.Recording) return
        if (after != null) this.after = after
        finalElapsedMs = elapsedMs()
        paused = false
        pausedAt = null
        phase = DictationPhase.Uploading
    }

    fun uploadAccepted(id: String) {
        phase = DictationPhase.Transcribing
        jobId = id
        mine = true
        message = null
        canRetry = false
        claiming = false
        deadline = clock() + STT_TIMEOUT_MS
    }

    /** A recording that produced no uploadable audio (nothing was said, mic denied). */
    fun fail(message: String) {
        phase = DictationPhase.Failed
        this.message = message
        claiming = false
        canRetry = hasLocalAudio || jobId != null
    }

    fun beginUploadRetry(): Boolean {
        if (!hasLocalAudio) return false
        phase = DictationPhase.Uploading
        message = null
        canRetry = false
        claiming = false
        deadline = 0L
        return true
    }

    /** Jobs of this scope a browser may pick up (nothing of its own is running). */
    fun canAdopt(): Boolean = phase == DictationPhase.Idle || phase == DictationPhase.Gone

    /**
     * Adopts the newest live job of the scope. A failed job is shown with its
     * error so this composer can play, retry or discard the server's copy.
     */
    fun adopt(jobs: List<SttJob>): Boolean {
        if (!canAdopt()) return false
        val job = jobs.firstOrNull { it.status != SttJobStatus.Gone } ?: return false
        jobId = job.id
        mine = false
        hasLocalAudio = false
        finalElapsedMs = 0L
        after = SttAfter.Insert
        claiming = false
        if (job.status == SttJobStatus.Error) {
            phase = DictationPhase.Failed
            message = job.error ?: TRANSCRIPTION_FAILED
            canRetry = true
        } else {
            phase = DictationPhase.Transcribing
            message = null
            canRetry = false
            deadline = clock() + STT_TIMEOUT_MS
        }
        return true
    }

    /** A failed job the server still holds audio for: retry it server-side. */
    fun serverRetryJobId(): String? = jobId

    fun retryAccepted(job: SttJob): DictationStep? = when (job.status) {
        SttJobStatus.Gone -> standDown()
        SttJobStatus.Error -> failStep(job.error ?: TRANSCRIPTION_FAILED)
        else -> {
            phase = DictationPhase.Transcribing
            jobId = job.id
            // `retrySttJob` makes the retrying browser the job's owner, so a
            // lost job is this composer's failure, not another's.
            mine = true
            message = null
            canRetry = false
            claiming = false
            deadline = clock() + STT_TIMEOUT_MS
            null
        }
    }

    /**
     * The server no longer has the job (restart, expiry, pruned tombstone):
     * only the local copy can be retried.
     */
    fun retryJobMissing(): DictationStep {
        jobId = null
        canRetry = hasLocalAudio
        return if (beginUploadRetry()) DictationStep.Wait else failStep(JOB_NOT_FOUND)
    }

    /** A kept recording whose upload never finished (process death): offer a re-upload. */
    fun restoreRecording(after: SttAfter, message: String) {
        reset()
        this.after = after
        hasLocalAudio = true
        fail(message)
    }

    fun retryRejected(message: String): DictationStep = failStep(message)

    /** `DELETE` without a claim: discard everywhere, including the upstream request. */
    fun discard() {
        reset()
    }

    /** Cancelled while recording. */
    fun cancelCapture() {
        reset()
    }

    /** `GET /api/stt/{jobId}` answer: `null` is a 404. */
    fun observe(job: SttJob?): DictationStep {
        if (phase != DictationPhase.Transcribing) return DictationStep.Wait
        if (expired()) return failStep(TIMED_OUT)
        return when (job?.status) {
            null -> {
                jobId = null
                if (mine) failStep(JOB_NOT_FOUND) else standDown()
            }
            SttJobStatus.Pending -> DictationStep.Wait
            SttJobStatus.Error -> failStep(job.error ?: TRANSCRIPTION_FAILED)
            SttJobStatus.Gone -> standDown()
            SttJobStatus.Done -> if (claiming) DictationStep.Wait else {
                claiming = true
                DictationStep.Claim
            }
        }
    }

    /** `DELETE ?claim=…` answer: `null` is a 404. */
    fun takeClaim(claim: SttClaim?): DictationStep {
        if (phase != DictationPhase.Transcribing) return DictationStep.Wait
        if (expired()) return failStep(TIMED_OUT)
        if (claim == null) {
            jobId = null
            return if (mine) failStep(JOB_NOT_FOUND) else standDown()
        }
        return when (claim.status) {
            SttJobStatus.Gone -> standDown()
            SttJobStatus.Error -> failStep(claim.error ?: TRANSCRIPTION_FAILED)
            SttJobStatus.Pending -> {
                // A claim on an unfinished job is a no-op; poll again.
                claiming = false
                DictationStep.Wait
            }
            SttJobStatus.Done -> {
                val text = claim.text?.trim()
                if (text.isNullOrEmpty()) {
                    // "done" without text: the recording browser keeps the first
                    // claim for 15s; repeat the claim with the same token.
                    DictationStep.Wait
                } else {
                    val owner = mine || claim.after != null
                    phase = DictationPhase.Idle
                    jobId = null
                    hasLocalAudio = false
                    message = null
                    canRetry = false
                    claiming = false
                    deadline = 0L
                    DictationStep.Delivered(text, claim.after ?: SttAfter.Insert, owner)
                }
            }
        }
    }

    private fun expired(): Boolean = deadline > 0 && clock() > deadline

    private fun failStep(message: String): DictationStep {
        fail(message)
        return DictationStep.Failed(message)
    }

    private fun standDown(): DictationStep {
        phase = DictationPhase.Gone
        jobId = null
        message = null
        canRetry = false
        hasLocalAudio = false
        claiming = false
        deadline = 0L
        return DictationStep.StandDown
    }

    companion object {
        /** `MAX_RECORDING_MS` in `hooks/useDictation.ts`. */
        const val MAX_RECORDING_MS = 300_000L

        /** The server gives the STT endpoint ~5 min; the deck waits a bit longer. */
        const val STT_TIMEOUT_MS = 6 * 60_000L
        const val STT_POLL_MS = 500L
        const val STT_ADOPT_MS = 4_000L

        const val TRANSCRIPTION_FAILED = "Transcription failed"
        const val TIMED_OUT = "Transcription timed out"
        const val JOB_NOT_FOUND = "Transcription job not found"
        const val NO_SPEECH = "No speech detected"
    }
}
