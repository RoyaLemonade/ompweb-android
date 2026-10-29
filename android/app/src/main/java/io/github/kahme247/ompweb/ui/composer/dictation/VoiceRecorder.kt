package io.github.kahme247.ompweb.ui.composer.dictation

import android.annotation.SuppressLint
import android.media.MediaRecorder
import java.io.File

/**
 * One server-bound capture, as an interface so the dictation view model runs
 * in JVM tests (the platform recorder needs a device). A new instance records
 * one take; [release] is the only cleanup call and is idempotent.
 */
interface VoiceRecorder {

    /** Starts writing audio to [file]; false when recording cannot start. */
    fun start(file: File): Boolean

    fun pause()

    fun resume()

    /** Current peak amplitude (0..32767) for the waveform. */
    fun maxAmplitude(): Int

    /** Stops the capture; true when the recorder wrote audio. */
    fun stop(): Boolean

    /** Releases the platform resources. */
    fun release()
}

/** [VoiceRecorder] over [MediaRecorder]: AAC in MPEG-4, mono, 44.1 kHz. */
class AndroidVoiceRecorder : VoiceRecorder {

    private var recorder: MediaRecorder? = null

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun start(file: File): Boolean {
        var created: MediaRecorder? = null
        return try {
            created = MediaRecorder()
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioSamplingRate(44_100)
            created.setAudioEncodingBitRate(96_000)
            created.setAudioChannels(1)
            created.setOutputFile(file.absolutePath)
            created.prepare()
            created.start()
            recorder = created
            true
        } catch (_: Exception) {
            runCatching { created?.release() }
            false
        }
    }

    override fun pause() {
        runCatching { recorder?.pause() }
    }

    override fun resume() {
        runCatching { recorder?.resume() }
    }

    override fun maxAmplitude(): Int =
        runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    override fun stop(): Boolean {
        val current = recorder ?: return false
        return try {
            current.stop()
            true
        } catch (_: RuntimeException) {
            // Stopped before the first frame: the file carries no audio.
            false
        }
    }

    override fun release() {
        val current = recorder
        recorder = null
        runCatching { current?.release() }
    }
}
