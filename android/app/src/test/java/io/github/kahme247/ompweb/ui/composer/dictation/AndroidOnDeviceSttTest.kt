package io.github.kahme247.ompweb.ui.composer.dictation

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.domain.DictationSegmentSource
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers

/**
 * The intent this app hands to the on-device recognizer, the model check in
 * front of it, and the errors it reports back.
 *
 * The endpointer extras are the load-bearing part of the fix: a live Pixel take
 * closed the microphone 500 ms after it opened and failed with "No speech
 * detected" before the user could speak, because the intent asked for a 500 ms
 * minimum input length (logcat: `onMicrophoneOpened` 16:07:00.526 →
 * `onMicrophoneCloseRequested` 16:07:01.026 → `NO_SPEECH_DETECTED`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class AndroidOnDeviceSttTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stt = AndroidOnDeviceStt(context)
    private lateinit var listener: RecordingListener

    private class RecordingListener : OnDeviceStt.Listener {
        val partials = mutableListOf<String>()
        val segments = mutableListOf<String>()
        val sources = mutableListOf<DictationSegmentSource>()
        var final: String? = null
        var error: OnDeviceSttError? = null

        override fun onPartial(text: String) {
            partials += text
        }

        override fun onSegment(text: String, source: DictationSegmentSource) {
            segments += text
            sources += source
        }

        override fun onFinal(text: String) {
            final = text
        }

        override fun onError(error: OnDeviceSttError) {
            this.error = error
        }
    }

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.reset()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        listener = RecordingListener()
    }

    private val shadow: ShadowSpeechRecognizer
        get() = Shadow.extract(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    /** The callbacks run on the main executor; a paused looper needs this. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start(language: String = "de-DE") = stt.start(language, listener)

    /** Starts a take and answers the model query as "installed". */
    private fun startListening(language: String = "de-DE"): android.content.Intent {
        start(language)
        shadow.triggerSupportResult(support(installed = listOf(language), supported = listOf(language)))
        idle()
        return checkNotNull(shadow.lastRecognizerIntent) { "the recognizer never started listening" }
    }

    /** A `RecognitionSupport` as the recognizer service would answer it. */
    private fun support(
        installed: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        supported: List<String> = emptyList(),
    ): RecognitionSupport {
        // Its constructor is a platform-internal API: the stub jar hides it, so
        // Robolectric's helper creates the instance without calling it.
        val support = ReflectionHelpers.newInstance(RecognitionSupport::class.java)
        mapOf(
            "mInstalledOnDeviceLanguages" to installed,
            "mPendingOnDeviceLanguages" to pending,
            "mSupportedOnDeviceLanguages" to supported,
        ).forEach { (name, value) ->
            RecognitionSupport::class.java.getDeclaredField(name).apply { isAccessible = true }.set(support, value)
        }
        return support
    }

    private fun errorOf(code: Int): OnDeviceSttError {
        ShadowSpeechRecognizer.reset()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val local = RecordingListener()
        stt.start("de-DE", local)
        shadow.triggerOnError(code)
        return local.error ?: error("no error reported for $code")
    }

    @Test
    fun `the take stays open across pauses so the next utterance is kept`() {
        val intent = startListening()

        assertEquals("de-DE", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(
            "the session outlives the speaker's pauses instead of ending on the first one",
            CONTINUOUS_LENGTH_MS,
            intent.getIntExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0),
        )
        assertFalse(
            "a complete-silence window would cut the take at the first pause",
            intent.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS),
        )
        assertFalse(
            "a possibly-complete silence window ends the take before the first word",
            intent.hasExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS),
        )
        assertEquals(
            "each utterance between two pauses is finalized as its own segment",
            RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
            intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION),
        )
        assertTrue(
            "recognizers without segmented sessions keep dictating across pauses",
            intent.getBooleanExtra(DICTATION_MODE, false),
        )
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertNull(listener.error)
    }

    @Test
    fun `the BCP-47 language reaches the recognizer and a blank one never does`() {
        assertEquals("de-DE", startListening("de-DE").getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))

        ShadowSpeechRecognizer.reset()
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val blank = RecordingListener()
        stt.start("  ", blank)
        assertEquals(
            "a blank tag cannot match a language pack; the recognizer is not asked",
            OnDeviceSttError.LanguageUnsupported,
            blank.error,
        )
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    @Test
    fun `recognizer errors name the cause instead of all reading no speech`() {
        assertEquals(OnDeviceSttError.Permission, errorOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(OnDeviceSttError.LanguageUnsupported, errorOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(OnDeviceSttError.Busy, errorOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(OnDeviceSttError.Busy, errorOf(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS))
        assertEquals(OnDeviceSttError.Client, errorOf(SpeechRecognizer.ERROR_CLIENT))
        assertEquals(OnDeviceSttError.Audio, errorOf(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(OnDeviceSttError.Offline, errorOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(OnDeviceSttError.Offline, errorOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(OnDeviceSttError.Failed, errorOf(SpeechRecognizer.ERROR_SERVER))
    }

    @Test
    fun `an instant silent give-up is not reported as the user's silence`() {
        startListening()
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        shadow.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(OnDeviceSttError.StoppedTooEarly, listener.error)
    }

    @Test
    fun `silence after a real listening window stays no speech`() {
        startListening()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        shadow.triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(OnDeviceSttError.NoSpeech, listener.error)
    }

    @Test
    fun `an installed model starts listening`() {
        val intent = startListening()
        assertNull(listener.error)
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    }

    @Test
    fun `a model that is downloading already is reported as downloading`() {
        start()
        shadow.triggerSupportResult(support(pending = listOf("de-DE"), supported = listOf("de-DE")))
        idle()
        assertEquals(OnDeviceSttError.LanguageDownloading, listener.error)
    }

    @Test
    fun `a missing model is downloaded instead of reported as silence`() {
        start()
        shadow.triggerSupportResult(support(supported = listOf("de-DE")))
        idle()
        assertEquals(OnDeviceSttError.LanguageDownloading, listener.error)
        assertEquals(
            "Android was asked for the take's language model",
            "de-DE",
            shadow.latestModelDownloadIntent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE),
        )
    }

    @Test
    fun `a language this phone cannot recognize is unsupported`() {
        start("ja-JP")
        shadow.triggerSupportResult(support(supported = listOf("de-DE", "en-US")))
        idle()
        assertEquals(OnDeviceSttError.LanguageUnsupported, listener.error)
    }

    @Test
    fun `a service that reports nothing still gets to listen`() {
        start()
        shadow.triggerSupportResult(support())
        idle()
        assertNull(listener.error)
        assertNotNull(shadow.lastRecognizerIntent)
    }

    @Test
    fun `a support query the service rejects is reported, not guessed`() {
        start()
        shadow.triggerSupportError(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        idle()
        assertEquals(OnDeviceSttError.LanguageUnsupported, listener.error)
    }

    @Test
    fun `a take the user ended does not start listening when the model answer arrives`() {
        start()
        stt.stop()
        shadow.triggerSupportResult(support(installed = listOf("de-DE"), supported = listOf("de-DE")))
        idle()
        assertNull("the microphone is never opened after the tap on Done", shadow.lastRecognizerIntent)
        assertNull(listener.error)
    }

    @Test
    fun `an empty result bundle never becomes a transcript`() {
        startListening()
        shadow.triggerOnResults(null)
        assertEquals("", listener.final)
    }

    @Test
    fun `partial results reach the listener`() {
        startListening()
        shadow.triggerOnPartialResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("hallo"))
        })
        assertEquals(listOf("hallo"), listener.partials)
    }

    @Test
    fun `a finalized utterance of a segmented session is reported as a segment`() {
        startListening()
        shadow.triggerOnSegmentResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("hallo du"))
        })
        assertEquals(listOf("hallo du"), listener.segments)
        assertEquals(
            "the segmented-session path names its source",
            listOf(DictationSegmentSource.SegmentResults),
            listener.sources,
        )
        assertEquals("a finalized utterance is no hypothesis", emptyList<String>(), listener.partials)
    }

    @Test
    fun `a partial flagged as final is a finalized utterance`() {
        startListening()
        shadow.triggerOnPartialResults(Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("hallo du"))
            putBoolean(EXTRA_FINAL_SEGMENT, true)
        })
        assertEquals(
            "recognizers without segmented sessions mark finalized utterances this way",
            listOf("hallo du"),
            listener.segments,
        )
        assertEquals(
            "the final_result path names its source",
            listOf(DictationSegmentSource.FinalPartial),
            listener.sources,
        )
        assertEquals(emptyList<String>(), listener.partials)
    }

    @Test
    fun `the end of a segmented session ends the take`() {
        startListening()
        shadow.triggerOnEndOfSegmentedSession()
        assertEquals(
            "the view model keeps what the segments produced when the final is blank",
            "",
            listener.final,
        )
    }

    private companion object {
        /**
         * The longest session the Google recognizer accepts for
         * `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS`; it keeps the session open
         * across the pauses between utterances.
         */
        const val CONTINUOUS_LENGTH_MS = 5 * 60 * 1000

        /** Google's own extra for continuous dictation (see `SpeechRecognitionImpl` in Chromium). */
        const val DICTATION_MODE = "android.speech.extra.DICTATION_MODE"

        /** Google's flag on a partial result that finalizes an utterance. */
        const val EXTRA_FINAL_SEGMENT = "final_result"
    }
}
