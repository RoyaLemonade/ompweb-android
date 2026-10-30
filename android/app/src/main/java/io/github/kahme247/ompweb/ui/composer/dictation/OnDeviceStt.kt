package io.github.kahme247.ompweb.ui.composer.dictation

import io.github.kahme247.ompweb.domain.DictationSegmentSource

/** Why on-device recognition ended without an error message from the server. */
enum class OnDeviceSttError {
    /** No recognizer installed (no Google app on this phone). */
    NoRecognizer,

    /** `RECORD_AUDIO` is missing. */
    Permission,

    /** The recognizer needs a network connection (a cloud recognizer without data). */
    Offline,

    /** This phone cannot recognize the language on-device at all. */
    LanguageUnsupported,

    /**
     * The language's on-device model is not installed and could not be
     * downloaded: the user has to install the language data.
     */
    LanguageMissing,

    /** The language's on-device model is not installed; Android is fetching it. */
    LanguageDownloading,

    /** Another app is using the microphone/recognizer. */
    Busy,

    /** The recognizer never started listening (client error). */
    Client,

    /** The microphone could not be read. */
    Audio,

    /** Silence for the recognizer's whole listening window: nothing was said. */
    NoSpeech,

    /**
     * The recognizer gave up without having heard anything, before the user had
     * the time to speak — a broken start, not the user's silence.
     */
    StoppedTooEarly,

    /** Anything else (server failure). */
    Failed,
}

/**
 * The platform speech recognizer behind on-device dictation, as an interface so
 * the view-model logic runs in JVM tests. [Listener] callbacks arrive on the
 * main thread, in order; the implementation destroys its recognizer after the
 * final transcript or an error, so a new [start] always builds a fresh one.
 */
interface OnDeviceStt {

    /**
     * This phone has an installed on-device recognition model
     * (`SpeechRecognizer.isOnDeviceRecognitionAvailable`, API 31+). A cloud
     * recognizer does not count: "on this phone" must never upload audio.
     */
    fun isOnDeviceAvailable(): Boolean

    /**
     * Starts listening on the on-device recognizer in [languageTag] (BCP-47,
     * e.g. `de-DE`). Reports [Listener.onPartial] while the user talks and
     * exactly one of [Listener.onFinal] / [Listener.onError] afterwards; with
     * [isOnDeviceAvailable] false it reports [OnDeviceSttError.NoRecognizer]
     * instead of falling back to a network recognizer. A missing language model
     * is reported as [OnDeviceSttError.LanguageDownloading] (Android was asked
     * to fetch it) or [OnDeviceSttError.LanguageMissing], never as silence.
     */
    fun start(languageTag: String, listener: Listener)

    /** End the utterance and wait for the final transcript. */
    fun stop()

    /** Abort without a transcript and destroy the recognizer. */
    fun cancel()

    /** Destroys the recognizer if one is still alive (idempotent). */
    fun release()

    interface Listener {
        /** The recognizer's hypothesis for the utterance it is in right now. */
        fun onPartial(text: String)

        /**
         * One utterance the recognizer finalized because the speaker paused.
         * One take spans several of them: on a Pixel the next hypothesis
         * starts with the new words only, so a segment has to be added to the
         * take instead of being replaced by what follows. [source] names the
         * callback that delivered it, so the same utterance reported on both
         * paths is added once while a repeated phrase is kept.
         */
        fun onSegment(text: String, source: DictationSegmentSource)

        fun onFinal(text: String)

        fun onError(error: OnDeviceSttError)
    }
}
