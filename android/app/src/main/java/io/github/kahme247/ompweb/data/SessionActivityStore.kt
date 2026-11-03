package io.github.kahme247.ompweb.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The newest user/assistant message this device has seen per session, epoch
 * millis by session id.
 *
 * `GET /api/sessions` has no "last message" field (`SessionInfo.modified` is
 * the session file's mtime, which omp also writes on a resume), so the chat
 * screen reports the transcript's newest message here and the workspace paints
 * that instead of the file time (see `domain/SessionActivity.kt`).
 */
interface SessionActivity {
    /** The known message times; a Compose screen collects this so a new entry repaints. */
    val known: StateFlow<Map<String, Long>>

    /**
     * Remember that [sessionId]'s newest message was sent at [at] (epoch
     * millis). An older value never replaces a newer one: pages, live frames
     * and cached seeds arrive out of order, and the newest stays the truth.
     */
    fun record(sessionId: String, at: Long)

    /** Forget every entry (logout, server switch, 401). */
    fun clear()
}

/**
 * [SessionActivity] backed by one small JSON file in `filesDir`.
 *
 * The map is a derived memo, not user data: a file that no longer parses is
 * dropped instead of crashing, and a write goes through a temp file so a killed
 * process never leaves half a document behind. Only the [maxEntries] newest
 * sessions are kept — a deleted session's entry is never looked up again, and
 * the list must not grow for the lifetime of the installation.
 */
class SessionActivityStore(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxEntries: Int = MAX_ENTRIES,
) : SessionActivity {

    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Mutex()
    private val _known = MutableStateFlow<Map<String, Long>>(emptyMap())
    override val known: StateFlow<Map<String, Long>> = _known.asStateFlow()

    /** Whether the file has been read; guarded by [lock]. */
    private var loaded = false

    /**
     * Bumped by every [clear] before its coroutine runs. A read that started
     * under an older generation may not merge its (pre-clear) file into the
     * map, or the next [record] would write the forgotten ids again.
     */
    @Volatile
    private var generation = 0L

    /** A clear is in flight: a late [record] belongs to nobody and is ignored. */
    @Volatile
    private var clearing = false

    init {
        scope.launch { ensureLoaded() }
    }

    override fun record(sessionId: String, at: Long) {
        if (sessionId.isBlank() || at <= 0) return
        // A frame that arrives while the state is being forgotten (logout, a
        // 401, a server switch) must not survive the clear.
        if (clearing) return
        val current = _known.value
        val previous = current[sessionId]
        if (previous != null && previous >= at) return
        val next = LinkedHashMap(current).apply { put(sessionId, at) }
        // Publish before the disk write: the chat screen and the workspace are
        // the same process, and the row must be right even if the process dies
        // before the file lands.
        _known.value = trim(next)
        scope.launch { persist() }
    }

    override fun clear() {
        // Synchronously first: readers see "forgotten" at once, and no read
        // that was already in flight may write the old map back.
        clearing = true
        generation++
        _known.value = emptyMap()
        scope.launch {
            try {
                lock.withLock {
                    loaded = true
                    withContext(io) { runCatching { file.delete() } }
                }
            } finally {
                clearing = false
            }
        }
    }

    /**
     * Read the file once. A [record] that lands while the read is in flight
     * keeps its (newer) value: the merge prefers the larger instant per id.
     * A [clear] during the read wins: its file is gone, so the answer is not
     * merged at all.
     */
    private suspend fun ensureLoaded() {
        lock.withLock {
            if (loaded) return
            val startedAt = generation
            val stored = withContext(io) { read() }
            if (startedAt != generation) return
            _known.value = trim(merge(stored, _known.value))
            loaded = true
        }
    }

    /**
     * Write the current map. A clear that started meanwhile owns the file: an
     * answer from a pre-clear load is never written back, which is what made
     * the forgotten ids reappear on the next record.
     */
    private suspend fun persist() {
        ensureLoaded()
        lock.withLock {
            if (!loaded || clearing) return
            val snapshot = _known.value
            withContext(io) { write(snapshot) }
        }
    }

    private fun read(): Map<String, Long> {
        if (!file.isFile) return emptyMap()
        val document = runCatching { json.decodeFromString(Document.serializer(), file.readText()) }.getOrNull()
        if (document == null) {
            runCatching { file.delete() }
            return emptyMap()
        }
        return document.sessions.filter { (id, at) -> id.isNotBlank() && at > 0 }
    }

    private fun write(known: Map<String, Long>): Boolean {
        val dir = file.parentFile ?: return false
        return runCatching {
            dir.mkdirs()
            val temp = File(dir, "${file.name}.tmp")
            temp.writeText(json.encodeToString(Document.serializer(), Document(known)))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.isSuccess
    }

    private fun merge(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val merged = LinkedHashMap(a)
        for ((id, at) in b) {
            val existing = merged[id]
            if (existing == null || at > existing) merged[id] = at
        }
        return merged
    }

    /** Keeps the [maxEntries] newest sessions. */
    private fun trim(known: Map<String, Long>): Map<String, Long> {
        if (known.size <= maxEntries) return known
        return known.entries
            .sortedByDescending { it.value }
            .take(maxEntries)
            .associateTo(LinkedHashMap()) { it.key to it.value }
    }

    @Serializable
    private data class Document(val sessions: Map<String, Long> = emptyMap())

    companion object {
        /**
         * Sessions remembered per installation. A workspace rarely holds more,
         * and the oldest entry is the one a new session replaces first.
         */
        const val MAX_ENTRIES: Int = 500

        const val FILE_NAME: String = "session-activity.json"
    }
}
