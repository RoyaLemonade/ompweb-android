package io.github.kahme247.ompweb.chat

import io.github.kahme247.ompweb.domain.TextInsertion

/**
 * The markdown block a "Quote" action inserts: every line of [text] prefixed
 * with `> ` (an empty line becomes a bare `>` so the quote stays one block).
 */
fun quoteBlock(text: String): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
    if (normalized.isEmpty()) return ""
    return normalized.lines().joinToString("\n") { line ->
        if (line.isEmpty()) ">" else "> $line"
    }
}

/**
 * Insert a quote at [caret]: the block plus one blank line, on its own line.
 * Text the user already typed after the caret stays behind the quote, and the
 * caret sits on the empty line under it, ready for the answer.
 */
fun quoteInsertion(text: String, caret: Int, quoted: String): TextInsertion {
    val block = quoteBlock(quoted)
    if (block.isEmpty()) return TextInsertion(text, caret.coerceIn(0, text.length))
    val position = caret.coerceIn(0, text.length)
    val before = text.substring(0, position)
    val lead = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
    val inserted = lead + block + "\n\n"
    return TextInsertion(before + inserted + text.substring(position), position + inserted.length)
}
