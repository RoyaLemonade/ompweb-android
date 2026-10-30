package io.github.kahme247.ompweb.data

import io.github.kahme247.ompweb.domain.ComposerImage
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One message waiting for the connection, with its images decoded into app
 * storage. [createdAt] keeps the send order stable across restarts.
 */
data class OutboxEntry(
    val id: String,
    val sessionId: String,
    val cwd: String?,
    val text: String,
    val images: List<ComposerImage>,
    val createdAt: Long,
)

/**
 * What the store found for one origin. [uncertain] counts records that were
 * claimed for delivery (`attempted`) when the process died: the POST may have
 * reached the server, so they are finalized as delivered instead of being sent
 * a second time.
 */
data class OutboxLoad(
    val entries: List<OutboxEntry> = emptyList(),
    val uncertain: Int = 0,
)

/**
 * The answer to [OutboxStore.claim]. Only [CLAIMED] lets the caller issue the
 * POST: [GONE] means the user cancelled or edited the record in the meantime,
 * [FAILED] means the "this POST may be on its way" mark could not be written
 * (a full disk, a locked file). Sending after [FAILED] would lose the
 * exactly-once guarantee, because a crash would resurrect the message.
 */
enum class OutboxClaim { CLAIMED, GONE, FAILED }

/**
 * The offline outbox: messages whose send never reached the server because the
 * device had no connection. It is scoped to one origin by directory (a server
 * switch must not deliver another installation's text) and lives in
 * `filesDir`, so it survives a process death and is wiped by
 * `clearLocalUserData` ([clearAll]).
 *
 * Exactly-once rule: [claim] persists "a POST for this message may be on its
 * way" *before* the request is issued. A record still claimed when the file is
 * read again (a killed process, a crash) is never re-sent — it is deleted and
 * reported through [OutboxLoad.uncertain]. Only a record the sender explicitly
 * [release]d (the request provably never left the device) returns to the queue.
 *
 * All operations are synchronous and synchronized: the view model calls them
 * on an IO dispatcher, one call at a time.
 */
class OutboxStore(
    private val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * The queued messages of [origin], oldest first. Records that are being
     * delivered right now (claimed) are hidden but kept: the sender still owns
     * them.
     */
    fun read(origin: String): OutboxLoad = synchronized(lock) {
        val dir = originDir(origin)
        val records = readRecords(dir)
        if (records.isEmpty()) return OutboxLoad()
        OutboxLoad(entries = records.filterNot { it.attempted }.mapNotNull { toEntry(dir, it) })
    }

    /**
     * Startup pass: a record still claimed here belongs to a process that died
     * between "POST is on its way" and the answer. It may have reached the
     * server, so it is deleted instead of being sent again, and the count is
     * reported ([OutboxLoad.uncertain]) so the UI can say so once.
     *
     * Records older than [MAX_AGE_MILLIS] go too (with their images): a chat
     * that was never opened again cannot deliver them, and a lost connection
     * must not fill app storage forever.
     */
    fun recover(origin: String): OutboxLoad = synchronized(lock) {
        val dir = originDir(origin)
        val records = readRecords(dir)
        if (records.isEmpty()) return OutboxLoad()
        val cutoff = clock() - MAX_AGE_MILLIS
        val (expired, alive) = records.partition { it.createdAt < cutoff }
        val (uncertain, pending) = alive.partition { it.attempted }
        if (expired.isNotEmpty() || uncertain.isNotEmpty()) {
            writeRecords(dir, pending)
            (expired + uncertain).forEach { deleteImages(dir, it) }
        }
        OutboxLoad(
            entries = pending.mapNotNull { toEntry(dir, it) },
            uncertain = uncertain.size,
        )
    }

    /** Put a message that could not be sent into the queue (no-op when empty). */
    fun enqueue(
        origin: String,
        sessionId: String,
        cwd: String?,
        text: String,
        images: List<ComposerImage>,
    ): OutboxEntry? = synchronized(lock) {
        if (text.isBlank() && images.isEmpty()) return null
        val dir = originDir(origin)
        val record = OutboxRecord(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            cwd = cwd,
            text = text,
            createdAt = clock(),
        )
        val refs = ArrayList<OutboxImageRef>(images.size)
        for (image in images) {
            val name = "${record.id}-${refs.size}.bin"
            val bytes = runCatching { Base64.getDecoder().decode(image.data) }.getOrNull() ?: continue
            val file = File(imagesDir(dir), name)
            val written = runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }.isSuccess
            if (written) refs += OutboxImageRef(file = name, mimeType = image.mimeType)
        }
        val stored = record.copy(images = refs)
        val records = readRecords(dir) + stored
        if (!writeRecords(dir, records)) {
            deleteImages(dir, stored)
            return null
        }
        toEntry(dir, stored)
    }

    /**
     * Mark [id] as being delivered, before its POST is issued. [OutboxClaim.GONE]
     * when the record is gone (cancelled by the user in the meantime),
     * [OutboxClaim.FAILED] when the mark could not be persisted.
     */
    fun claim(origin: String, id: String): OutboxClaim = synchronized(lock) {
        val dir = originDir(origin)
        val records = readRecords(dir)
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return OutboxClaim.GONE
        if (records[index].attempted) return OutboxClaim.CLAIMED
        val next = records.toMutableList().apply { this[index] = this[index].copy(attempted = true) }
        if (writeRecords(dir, next)) OutboxClaim.CLAIMED else OutboxClaim.FAILED
    }

    /**
     * The request provably never reached the server (connection refused, no
     * route, unknown host): put the message back at its place for the next
     * reconnect. Returns false when the queue file could not be rewritten (the
     * record then stays claimed and is finalized as uncertain on the next
     * start — never sent twice).
     */
    fun release(origin: String, id: String): Boolean = synchronized(lock) {
        val dir = originDir(origin)
        val records = readRecords(dir)
        val index = records.indexOfFirst { it.id == id }
        if (index < 0 || !records[index].attempted) return true
        val next = records.toMutableList().apply {
            this[index] = this[index].copy(attempted = false)
        }
        writeRecords(dir, next)
    }

    /**
     * Drop a queued message (cancel, edit-into-composer, delivered). Returns
     * false when the record could not be removed: its images are then kept,
     * because the record is still queued and must stay deliverable.
     */
    fun remove(origin: String, id: String): Boolean = synchronized(lock) {
        val dir = originDir(origin)
        val records = readRecords(dir)
        val record = records.firstOrNull { it.id == id } ?: return true
        if (!writeRecords(dir, records.filterNot { it.id == id })) return false
        deleteImages(dir, record)
        true
    }

    /** Drop everything (sign-out, server switch, 401). */
    fun clearAll() {
        synchronized(lock) { runCatching { root.deleteRecursively() } }
    }

    /** Every origin's queue, for tests and diagnostics. */
    fun origins(): List<String> = synchronized(lock) {
        root.listFiles()
            ?.filter { it.isDirectory && File(it, RECORDS_FILE).isFile }
            ?.map { it.name }
            .orEmpty()
    }

    // --------------------------------------------------------------- storage

    private fun originDir(origin: String): File = File(root, hash(origin))

    private fun imagesDir(dir: File): File = File(dir, IMAGES_DIR)

    private fun readRecords(dir: File): List<OutboxRecord> {
        val file = File(dir, RECORDS_FILE)
        if (!file.isFile) return emptyList()
        val parsed = runCatching {
            json.decodeFromString(Envelope.serializer(), file.readText())
        }.getOrNull()
        if (parsed == null) {
            runCatching { file.delete() }
            return emptyList()
        }
        val kept = ArrayList<OutboxRecord>(parsed.entries.size)
        val dropped = ArrayList<OutboxRecord>()
        for (record in parsed.entries) {
            if (record.sessionId.isNotBlank() && (record.text.isNotBlank() || record.images.isNotEmpty())) {
                kept += record
            } else {
                dropped += record
            }
        }
        if (dropped.isNotEmpty()) {
            // A record that cannot render can never be delivered, edited or
            // cancelled: keeping it (and its images) only leaks app storage.
            writeRecords(dir, kept)
            dropped.forEach { deleteImages(dir, it) }
        }
        return kept
    }

    private fun writeRecords(dir: File, records: List<OutboxRecord>): Boolean {
        if (records.isEmpty()) {
            return runCatching {
                val file = File(dir, RECORDS_FILE)
                if (file.exists()) file.delete()
                true
            }.getOrDefault(false)
        }
        return runCatching {
            dir.mkdirs()
            val body = json.encodeToString(Envelope.serializer(), Envelope(records))
            val file = File(dir, RECORDS_FILE)
            val temp = File(dir, "$RECORDS_FILE.tmp")
            FileOutputStream(temp).use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
                // Without the sync the rename can publish a file whose pages
                // are still in the cache; a killed process would then read an
                // empty queue and every waiting message would be gone.
                out.fd.sync()
            }
            // POSIX `rename` replaces the target in one step, so a process
            // death leaves either the old queue or the new one — the old
            // delete-then-rename order lost the whole queue in that window.
            // Only a filesystem that refuses an existing target needs the
            // delete first.
            if (temp.renameTo(file)) return@runCatching true
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }.getOrDefault(false)
    }

    private fun toEntry(dir: File, record: OutboxRecord): OutboxEntry? {
        val images = record.images.mapNotNull { ref ->
            val file = File(imagesDir(dir), ref.file)
            if (!file.isFile) return@mapNotNull null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
            ComposerImage(data = Base64.getEncoder().encodeToString(bytes), mimeType = ref.mimeType)
        }
        if (record.text.isBlank() && images.isEmpty()) return null
        return OutboxEntry(
            id = record.id,
            sessionId = record.sessionId,
            cwd = record.cwd,
            text = record.text,
            images = images,
            createdAt = record.createdAt,
        )
    }

    private fun deleteImages(dir: File, record: OutboxRecord) {
        record.images.forEach { ref -> runCatching { File(imagesDir(dir), ref.file).delete() } }
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(2 * digest.size)
        for (byte in digest) hex.append(HEX[(byte.toInt() shr 4) and 0x0f]).append(HEX[byte.toInt() and 0x0f])
        return hex.substring(0, 32)
    }

    @Serializable
    private data class Envelope(val entries: List<OutboxRecord> = emptyList())

    @Serializable
    private data class OutboxRecord(
        val id: String,
        val sessionId: String,
        val cwd: String? = null,
        val text: String = "",
        val images: List<OutboxImageRef> = emptyList(),
        val createdAt: Long = 0,
        /** A POST for this record may be on its way; it is never sent twice. */
        val attempted: Boolean = false,
    )

    @Serializable
    private data class OutboxImageRef(val file: String, val mimeType: String)

    companion object {
        private const val RECORDS_FILE = "queue.json"
        private const val IMAGES_DIR = "images"

        /**
         * A message the app could not deliver for this long is dropped when the
         * queue is read at startup. Its chat was never opened again, so no
         * delivery can ever happen; keeping it (and its images) would fill app
         * storage without bound.
         */
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
