package io.github.kahme247.ompweb.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
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

/** A user-defined snippet: a title and the text the composer inserts for it. */
@Serializable
data class ChatTemplate(
    val id: String,
    val title: String,
    val text: String,
)

/** One palette/command entry of a template, with its (deduplicated) `/name`. */
data class TemplateCommand(val template: ChatTemplate, val name: String)

/**
 * The `/name` a template answers to. Titles are prose ("Review checklist"), so
 * the command is a slug of them; punctuation that a slash command cannot carry
 * is dropped and a title without any usable character falls back to
 * [FALLBACK_COMMAND].
 */
fun templateCommandName(title: String): String {
    val slug = buildString {
        for (char in title.trim().lowercase()) {
            when {
                char.isLetterOrDigit() -> append(char)
                char == ' ' || char == '-' || char == '_' -> append('-')
            }
        }
    }.trim('-').replace(Regex("-{2,}"), "-")
    return slug.ifEmpty { FALLBACK_COMMAND }
}

private const val FALLBACK_COMMAND = "template"

/**
 * Templates resolved to commands in their stored order. Two templates whose
 * titles slug to the same name get `-2`, `-3`, … so a name always addresses
 * exactly one template.
 */
fun templateCommands(templates: List<ChatTemplate>): List<TemplateCommand> {
    val taken = HashSet<String>()
    return templates.map { template ->
        val base = templateCommandName(template.title)
        var name = base
        var suffix = 1
        while (!taken.add(name)) {
            suffix++
            name = "$base-$suffix"
        }
        TemplateCommand(template, name)
    }
}

/**
 * Expand a leading `/name` typed as a template command. Returns the template's
 * text (plus any arguments the user appended after the name), or null when the
 * text is not a template command.
 */
fun expandTemplateCommand(text: String, templates: List<ChatTemplate>): String? {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return null
    val body = trimmed.removePrefix("/")
    val name = body.substringBefore(' ').substringBefore('\n').substringBefore('\t').lowercase()
    if (name.isEmpty()) return null
    val command = templateCommands(templates).firstOrNull { it.name == name } ?: return null
    val rest = body.substring(name.length).trim()
    val content = command.template.text.trimEnd()
    return if (rest.isEmpty()) content else "$content\n\n$rest"
}

/**
 * The templates file, as a tolerant reader/writer. A file that no longer
 * parses is treated as empty (it is user data the app can rewrite, never a
 * reason to crash); blank titles or texts are dropped instead of rendering an
 * empty menu row.
 */
class TemplateRepository(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun load(): List<ChatTemplate> {
        if (!file.isFile) return emptyList()
        val document = runCatching { json.decodeFromString(Document.serializer(), file.readText()) }.getOrNull()
        if (document == null) {
            runCatching { file.delete() }
            return emptyList()
        }
        return document.templates.filter { it.title.isNotBlank() && it.text.isNotBlank() }
    }

    /**
     * Write the list and report whether the file now holds it. The temporary
     * file is moved over the old one in a single step, so a crash never leaves
     * a half-written snippet list, and a move that fails (locked file, full
     * disk) is reported instead of passing for a save — the store must never
     * publish a list the file does not have.
     */
    fun save(templates: List<ChatTemplate>): Boolean = runCatching {
        val dir = file.parentFile
        dir?.mkdirs()
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText(json.encodeToString(Document.serializer(), Document(templates)))
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess

    @Serializable
    private data class Document(val templates: List<ChatTemplate> = emptyList())
}

/**
 * Per-device templates (not per origin): a snippet is the user's own wording
 * and stays useful after switching servers. Writes are serialized, so two
 * quick edits of the settings screen cannot drop one of them, and the published
 * [state] is the file's content after each write.
 */
class TemplateStore(
    root: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val repository = TemplateRepository(File(root, FILE_NAME))
    private val lock = Mutex()
    private val _state = MutableStateFlow<List<ChatTemplate>>(emptyList())
    val state: StateFlow<List<ChatTemplate>> = _state.asStateFlow()

    /** Whether the file has been read; guarded by [lock]. */
    private var loaded = false

    init {
        scope.launch { ensureLoaded() }
    }

    /**
     * Read the file once. Every mutation waits for this, so an add that
     * arrives while the initial read is still on its way cannot be overwritten
     * by the (older) file content.
     */
    private suspend fun ensureLoaded() {
        lock.withLock {
            if (loaded) return
            _state.value = withContext(io) { repository.load() }
            loaded = true
        }
    }

    /** The current list without waiting for the state collector. */
    fun current(): List<ChatTemplate> = _state.value

    fun add(title: String, text: String) {
        val template = ChatTemplate(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            text = text.trim(),
        )
        if (template.title.isEmpty() || template.text.isEmpty()) return
        mutate { it + template }
    }

    fun update(id: String, title: String, text: String) {
        val cleanTitle = title.trim()
        val cleanText = text.trim()
        if (cleanTitle.isEmpty() || cleanText.isEmpty()) return
        mutate { list ->
            list.map { if (it.id == id) it.copy(title = cleanTitle, text = cleanText) else it }
        }
    }

    fun delete(id: String) {
        mutate { list -> list.filterNot { it.id == id } }
    }

    /** Move a template one slot up ([delta] = -1) or down (+1), clamped. */
    fun move(id: String, delta: Int) {
        mutate { list ->
            val index = list.indexOfFirst { it.id == id }
            if (index < 0) return@mutate list
            val target = (index + delta).coerceIn(0, list.lastIndex)
            if (target == index) return@mutate list
            list.toMutableList().apply { add(target, removeAt(index)) }
        }
    }

    private fun mutate(transform: (List<ChatTemplate>) -> List<ChatTemplate>) {
        scope.launch {
            ensureLoaded()
            lock.withLock {
                val next = transform(_state.value)
                if (next == _state.value) return@withLock
                // A failed write is not a save: publishing the list anyway
                // showed a snippet that was gone after the next start.
                if (withContext(io) { repository.save(next) }) {
                    _state.value = next
                }
            }
        }
    }

    companion object {
        private const val FILE_NAME = "templates.json"
    }
}
