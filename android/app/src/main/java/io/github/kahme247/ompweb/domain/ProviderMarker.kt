package io.github.kahme247.ompweb.domain

/**
 * The compact marker shown before an account's e-mail wherever omp reports
 * provider usage or stored accounts: a two-letter monogram, the alias letter
 * when the provider id carries one (`commandcode-b` -> `CC` + `b`), and the
 * full id for the tooltip and the accessibility label.
 *
 * [baseId] is the provider without its alias, lower-cased and joined with
 * `-`, so every alias of one provider keeps the same tint.
 */
data class ProviderMarker(
    val monogram: String,
    val alias: String?,
    /** The provider id exactly as omp reported it, only trimmed. */
    val label: String,
    val baseId: String,
)

/**
 * Fixed monograms for the providers omp knows, longest id first:
 * `openai-codex` also starts with `openai-`, and the longer id must win.
 */
private val KNOWN_PROVIDER_MONOGRAMS: List<Pair<String, String>> = listOf(
    "commandcode" to "CC",
    "openai-codex" to "OA",
    "openai" to "OA",
    "anthropic" to "AN",
    "google" to "GO",
    "gemini" to "GO",
    "xai" to "XA",
    "deepseek" to "DS",
    "openrouter" to "OR",
    "mistral" to "MI",
)

/** The separators omp uses in provider ids (`commandcode-a`, `my_custom-x`). */
private val PROVIDER_SEPARATORS = charArrayOf('-', '_', '.', ':', '/', ' ')

/**
 * Derives the marker of a provider id without any hard-coded account list:
 * known ids use their shared monogram, anything else uses the first two
 * letters of the id, and a trailing single character after a separator is the
 * alias omp appended (`commandcode-a` -> `a`). A longer last part is part of
 * the provider id (`openai-codex`, `deepseek-r1`), not an alias.
 */
fun providerMarker(providerId: String): ProviderMarker {
    val label = providerId.trim()
    val parts = label.split(*PROVIDER_SEPARATORS)
        .map { part -> part.filter(Char::isLetterOrDigit) }
        .filter { it.isNotEmpty() }
    if (parts.isEmpty()) return ProviderMarker("?", null, label, "")
    val alias = parts.last()
        .takeIf { parts.size > 1 && it.length == 1 }
        ?.lowercase()
    val baseParts = if (alias != null) parts.dropLast(1) else parts
    val baseId = baseParts.joinToString("-").lowercase()
    val monogram = KNOWN_PROVIDER_MONOGRAMS
        .firstOrNull { (id, _) -> baseId == id || baseId.startsWith("$id-") }
        ?.second
        ?: baseParts.joinToString("").take(2).uppercase()
    return ProviderMarker(monogram, alias, label, baseId)
}
