package io.github.kahme247.ompweb.ui.composer.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.kahme247.ompweb.domain.DictationSegmentSource

/**
 * [OnDeviceStt] over Android's [SpeechRecognizer].
 *
 * Only the on-device recognizer is ever built (`createOnDeviceSpeechRecognizer`,
 * API 31+); without an installed on-device model the class reports
 * [OnDeviceSttError.NoRecognizer] instead of falling back to the default
 * recognizer, which may send the audio over the network while the UI promises
 * on-device processing. The intent asks for partial results, a session that
 * outlives the pauses between utterances (each utterance is finalized as its
 * own segment) and offline preference: a pause must not end the take or throw
 * away what was said before it.
 *
 * The language model is checked before listening (API 33+,
 * `checkRecognitionSupport`): every take is a question to a recognizer that
 * may not have the model for the picked language, and a silent give-up there
 * reads like the user said nothing. A missing model is downloaded
 * (`triggerModelDownload`) and reported as such; a language this phone cannot
 * recognize on-device is reported as unsupported.
 *
 * `RECORD_AUDIO` is requested by the composer before dictation starts, so this
 * class never asks for permissions itself.
 */
class AndroidOnDeviceStt(context: Context) : OnDeviceStt {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** The support-query and model-download callbacks run on the main thread. */
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)

    private var recognizer: SpeechRecognizer? = null
    private var listener: OnDeviceStt.Listener? = null

    /** The intent the current take runs with; the model download repeats it. */
    private var activeIntent: Intent? = null

    /**
     * When listening started. A NO_MATCH/SPEECH_TIMEOUT that arrives right
     * after is the recognizer giving up on its own, not the user's silence.
     */
    private var listeningSince = 0L

    /** The user ended this take; a late model-check answer must not start it. */
    private var stopRequested = false

    override fun isOnDeviceAvailable(): Boolean = runCatching {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
    }.getOrDefault(false)

    // The composer requests RECORD_AUDIO before dictation starts; lint cannot
    // see across that call boundary.
    @SuppressLint("MissingPermission")
    override fun start(languageTag: String, listener: OnDeviceStt.Listener) {
        if (!onMainThread()) {
            main.post { start(languageTag, listener) }
            return
        }
        release()
        this.listener = listener
        if (!isOnDeviceAvailable()) {
            fail(OnDeviceSttError.NoRecognizer)
            return
        }
        // The service matches the extra against its language packs; a blank tag
        // cannot match anything and ends the take without a word.
        val language = languageTag.trim()
        if (language.isEmpty()) {
            fail(OnDeviceSttError.LanguageUnsupported)
            return
        }
        // The recognizer factory itself needs API 31: spell the level out so
        // lint sees the guard instead of only the `isOnDeviceAvailable` call.
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            }.getOrNull()
        } else {
            null
        }
        if (created == null) {
            fail(OnDeviceSttError.NoRecognizer)
            return
        }
        recognizer = created
        created.setRecognitionListener(RecognitionAdapter())
        val intent = recognitionIntent(language)
        activeIntent = intent
        if (checkModel(created, intent, language)) return
        listen(created, intent)
    }

    override fun stop() {
        onMain { current ->
            // The model check may still be in flight; a take the user ended
            // must not start listening when its answer arrives.
            stopRequested = true
            runCatching { current.stopListening() }
        }
    }

    override fun cancel() {
        onMain { current -> runCatching { current.cancel() } }
        release()
    }

    override fun release() {
        if (!onMainThread()) {
            main.post { release() }
            return
        }
        val current = recognizer
        recognizer = null
        listener = null
        activeIntent = null
        stopRequested = false
        if (current != null) {
            runCatching { current.setRecognitionListener(null) }
            runCatching { current.destroy() }
        }
    }

    private fun onMain(block: (SpeechRecognizer) -> Unit) {
        if (!onMainThread()) {
            main.post { onMain(block) }
            return
        }
        recognizer?.let(block)
    }

    private fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /**
     * The intent for one take.
     *
     * One take spans the pauses between utterances. The recognizer finalizes
     * an utterance when the speaker stops talking and keeps listening: with
     * segmented session mode (API 33+) each of those utterances arrives as a
     * segment, and on services without it Google's dictation mode keeps the
     * session open while it flags the finalized utterances in the partial
     * results (Chromium's `SpeechRecognitionImpl` sends exactly these extras
     * for a continuous Web Speech session). Ending the take at the first
     * pause instead lost everything the speaker said afterwards, and a
     * complete-silence window asked the recognizer to cut the session there.
     *
     * No `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS` of half a second: the
     * on-device recognizer ends the input once that much audio has arrived, so
     * such a minimum closed the microphone half a second after the tap and
     * answered "No speech detected" before the user could start (logcat on a
     * Pixel: microphone opened 16:07:00.526, close requested 16:07:01.026,
     * `NO_SPEECH_DETECTED`). A long one is what keeps the session open.
     *
     * The extras are spelled as integers: recognizer implementations read them
     * with getIntExtra (a Long silently falls back to their default and would
     * never keep the session open).
     */
    private fun recognitionIntent(language: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Legacy continuous dictation; current Google recognizers ignore it.
            putExtra(EXTRA_DICTATION_MODE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SESSION_MS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The session length is also the segmentation rule: an
                // utterance is ready when the speaker pauses, the session
                // itself runs on.
                putExtra(
                    RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                )
            }
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    /**
     * Asks the recognizer which on-device model it has for [language] (API 33+)
     * and acts on the answer. Returns true when the check took over the take:
     * either it starts listening from the answer or it ends with a model error.
     */
    private fun checkModel(created: SpeechRecognizer, intent: Intent, language: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val callback = object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                applyModelAnswer(created, intent, language, support)
            }

            override fun onError(error: Int) {
                applyModelError(created, error)
            }
        }
        return runCatching { created.checkRecognitionSupport(intent, mainExecutor, callback) }.isSuccess
    }

    /** [checkModel]'s answer for the take that is in flight. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyModelAnswer(
        created: SpeechRecognizer,
        intent: Intent,
        language: String,
        support: RecognitionSupport,
    ) {
        if (!currentTake(created)) return
        when (modelStatus(support, language)) {
            ModelStatus.Installed -> listen(created, intent)
            ModelStatus.Pending -> fail(OnDeviceSttError.LanguageDownloading)
            ModelStatus.Missing -> fail(requestDownloadOr(OnDeviceSttError.LanguageMissing))
            ModelStatus.Unsupported -> fail(OnDeviceSttError.LanguageUnsupported)
        }
    }

    private fun applyModelError(created: SpeechRecognizer, error: Int) {
        if (!currentTake(created)) return
        fail(errorOf(error))
    }

    /**
     * Whether an answer still belongs to the take that is running: the user may
     * have stopped, cancelled or started another one while the check was out.
     */
    private fun currentTake(created: SpeechRecognizer): Boolean =
        recognizer === created && listener != null

    private enum class ModelStatus {
        /** The model is on this phone. */
        Installed,

        /** Android is already downloading it. */
        Pending,

        /** The phone knows this language but the model is not installed yet. */
        Missing,

        /** This phone cannot recognize the language on-device. */
        Unsupported,
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun modelStatus(support: RecognitionSupport, language: String): ModelStatus {
        val installed = support.installedOnDeviceLanguages.orEmpty()
        val pending = support.pendingOnDeviceLanguages.orEmpty()
        val supported = support.supportedOnDeviceLanguages.orEmpty()
        return when {
            installed.has(language) -> ModelStatus.Installed
            pending.has(language) -> ModelStatus.Pending
            // A service that reports nothing cannot be judged here; let
            // startListening answer instead of inventing a model error.
            supported.isEmpty() -> ModelStatus.Installed
            supported.has(language) -> ModelStatus.Missing
            else -> ModelStatus.Unsupported
        }
    }

    private fun List<String>.has(language: String): Boolean =
        any { it.equals(language, ignoreCase = true) }

    /**
     * Asks Android to download the model for the current take's language.
     * Returns [OnDeviceSttError.LanguageDownloading] when the request went out
     * and [fallback] when this phone cannot download it.
     */
    // The one-argument form has existed since API 33; the listener overload is
    // API 34 and this flow needs neither progress nor a completion callback —
    // the next tap re-runs the model check.
    @Suppress("DEPRECATION")
    private fun requestDownloadOr(fallback: OnDeviceSttError): OnDeviceSttError {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return fallback
        val current = recognizer ?: return fallback
        val intent = activeIntent ?: return fallback
        val requested = runCatching { current.triggerModelDownload(intent) }.isSuccess
        return if (requested) OnDeviceSttError.LanguageDownloading else fallback
    }

    private fun listen(recognizer: SpeechRecognizer, intent: Intent) {
        if (stopRequested) return
        listeningSince = SystemClock.elapsedRealtime()
        val started = runCatching { recognizer.startListening(intent) }.isSuccess
        if (!started) fail(OnDeviceSttError.Client)
    }

    /** Reports [error] once to the listener of this take and destroys the recognizer. */
    private fun fail(error: OnDeviceSttError) {
        val target = listener
        release()
        target?.onError(error)
    }

    /** Reports the take's final transcript once and destroys the recognizer. */
    private fun endTake(text: String) {
        val target = listener
        release()
        target?.onFinal(text)
    }

    /** Forwards the callbacks, then destroys the recognizer exactly once. */
    private inner class RecognitionAdapter : RecognitionListener {

        /**
         * A hypothesis of the utterance in progress. Google's recognizer flags
         * the partial that finalizes an utterance with `final_result` when the
         * service has no segmented session (Chromium's `SpeechRecognitionImpl`
         * relies on the same flag), so that one is a segment.
         */
        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            if (text.isBlank()) return
            if (partialResults?.getBoolean(EXTRA_FINAL_SEGMENT, false) == true) {
                listener?.onSegment(text, DictationSegmentSource.FinalPartial)
            } else {
                listener?.onPartial(text)
            }
        }

        /** API 33+: one utterance between two pauses, already final. */
        override fun onSegmentResults(segmentResults: Bundle) {
            val text = firstResult(segmentResults) ?: return
            if (text.isBlank()) return
            listener?.onSegment(text, DictationSegmentSource.SegmentResults)
        }

        /** API 33+: the segmented session is over; the view model keeps what it heard. */
        override fun onEndOfSegmentedSession() {
            if (listener != null) endTake("")
        }

        override fun onResults(results: Bundle?) {
            // Android promises the full speech since onReadyForSpeech; a
            // continuous session may end with an empty bundle instead, and the
            // view model then keeps what the segments produced.
            endTake(firstResult(results).orEmpty())
        }

        override fun onError(error: Int) {
            val mapped = errorOf(error)
            val early = mapped == OnDeviceSttError.NoSpeech &&
                SystemClock.elapsedRealtime() - listeningSince < EARLY_END_MS
            when {
                early -> fail(OnDeviceSttError.StoppedTooEarly)
                // The recognizer just told us the model is missing: fetch it, so
                // the next tap works, and report that rather than silence.
                mapped == OnDeviceSttError.LanguageMissing ->
                    fail(requestDownloadOr(OnDeviceSttError.LanguageMissing))
                else -> fail(mapped)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String? =
        runCatching {
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        }.getOrNull()

    private companion object {
        /**
         * The length of one take: the recognizer keeps listening across the
         * pauses between utterances and finalizes each of them. Five minutes is
         * the longest session the on-device recognizer accepts for this extra.
         */
        const val SESSION_MS = 5 * 60 * 1_000

        /** Legacy continuous-dictation switch of the Google recognizer. */
        const val EXTRA_DICTATION_MODE = "android.speech.extra.DICTATION_MODE"

        /** Google's flag on the partial result that finalizes an utterance. */
        const val EXTRA_FINAL_SEGMENT = "final_result"

        /**
         * A NO_MATCH/SPEECH_TIMEOUT inside this window never waited for the
         * user; the recognizer's own listening window is several seconds.
         */
        const val EARLY_END_MS = 1_000L

        fun errorOf(error: Int): OnDeviceSttError = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> OnDeviceSttError.NoSpeech
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> OnDeviceSttError.Permission
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            -> OnDeviceSttError.Offline
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> OnDeviceSttError.LanguageUnsupported
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> OnDeviceSttError.LanguageMissing
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            -> OnDeviceSttError.Busy
            SpeechRecognizer.ERROR_AUDIO -> OnDeviceSttError.Audio
            SpeechRecognizer.ERROR_CLIENT -> OnDeviceSttError.Client
            else -> OnDeviceSttError.Failed
        }
    }
}
