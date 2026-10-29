package io.github.kahme247.ompweb.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the app knows about the configured server's transcription service.
 *
 * A server origin alone says nothing: a stock ompweb serves `/api/stt` and
 * answers 501 "STT not configured" unless `OMP_WEB_STT_ENDPOINT` or
 * `OMP_WEB_STT_MODEL` is set, and `/api/app-info` carries no capability flag
 * for it (neither this server nor upstream PR #324). The only honest signal is
 * the route itself: the upload checks `readSttConfig()` before it parses the
 * audio, so a small POST without audio tells [Unknown] from [Available] and
 * [NotConfigured] without uploading a recording.
 */
enum class ServerSttSupport {
    /** Not asked yet, or the probe could not answer (offline, signed out, 5xx). */
    Unknown,

    /** The server has an STT provider configured and transcribes uploads. */
    Available,

    /** The server answered 501, or has no `/api/stt` route at all. */
    NotConfigured,
}

/**
 * App-wide, per-origin answer to "can the configured server transcribe?".
 *
 * Auto dictation decides before the microphone opens: uploading a take to a
 * server without STT loses it (the server keeps the audio and can only answer
 * 501). The answer is therefore probed once per origin
 * ([io.github.kahme247.ompweb.net.SttApi.serverSupport]), cached here, and
 * refreshed at app start and after a server switch (both are a new process or
 * a new origin key). A 501 on a real upload marks the origin too, so no later
 * Auto take asks again. An [ServerSttSupport.Unknown] answer is never cached:
 * the next attempt may reach a server that was only briefly unreachable.
 */
class ServerSttRegistry {

    private val answers = HashMap<String, ServerSttSupport>()
    private val probeLock = Mutex()

    fun get(origin: String?): ServerSttSupport =
        if (origin == null) {
            ServerSttSupport.Unknown
        } else {
            synchronized(answers) { answers[origin] } ?: ServerSttSupport.Unknown
        }

    /** Records a definitive answer (a probe or a real upload's 501). */
    fun mark(origin: String?, support: ServerSttSupport) {
        if (origin == null || support == ServerSttSupport.Unknown) return
        synchronized(answers) { answers[origin] = support }
    }

    /**
     * The cached answer, or [probe] once. Concurrent callers for the same
     * origin wait for the one probe; a probe that fails or answers [Unknown]
     * is not cached and is retried by the next caller.
     */
    suspend fun ensure(origin: String, probe: suspend () -> ServerSttSupport): ServerSttSupport {
        val known = get(origin)
        if (known != ServerSttSupport.Unknown) return known
        return probeLock.withLock {
            val again = get(origin)
            if (again != ServerSttSupport.Unknown) {
                again
            } else {
                val answer = try {
                    probe()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ServerSttSupport.Unknown
                }
                mark(origin, answer)
                answer
            }
        }
    }
}
