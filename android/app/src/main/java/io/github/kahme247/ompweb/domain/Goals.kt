package io.github.kahme247.ompweb.domain

import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * omp's goal mode over RPC (`{type:"goal"}`), which replaces the old
 * client-only `/goal` banner. omp is the single source of truth: every change
 * arrives as a `goal_updated` event and `get_state.goal` carries the same
 * snapshot on reconnect.
 */
data class GoalState(
    val id: String,
    val objective: String,
    /** `active` or `paused`; dropped/completed goals are not shown. */
    val status: String,
    val tokenBudget: Long? = null,
    val tokensUsed: Long = 0,
    val timeUsedSeconds: Long = 0,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
) {
    val paused: Boolean get() = status == "paused"

    /** The banner shows live goals only; a dropped/completed one is gone. */
    val visible: Boolean get() = status == "active" || status == "paused"
}

/** Parse one `{id, objective, status, …}` goal object; null when absent/invalid. */
fun parseGoal(element: JsonElement?): GoalState? {
    val root = element as? JsonObject ?: return null
    val id = root.string("id")?.trim().orEmpty()
    val objective = root.string("objective")?.trim().orEmpty()
    val status = root.string("status")?.trim().orEmpty()
    if (id.isEmpty() || objective.isEmpty() || status.isEmpty()) return null
    return GoalState(
        id = id,
        objective = objective,
        status = status,
        tokenBudget = root.long("tokenBudget")?.takeIf { it > 0 },
        tokensUsed = root.long("tokensUsed")?.coerceAtLeast(0) ?: 0,
        timeUsedSeconds = root.long("timeUsedSeconds")?.coerceAtLeast(0) ?: 0,
        createdAt = root.long("createdAt"),
        updatedAt = root.long("updatedAt"),
    )
}

/** The `goal` object of a `goal` RPC reply (`data.goal`), or of `get_state`. */
fun parseGoalFromCommandData(data: JsonElement?): GoalState? =
    parseGoal((data as? JsonObject)?.get("goal"))

/**
 * True when omp (or the server wrapping it) does not know the `goal` command.
 * Older omp builds answer "Unknown command: goal"; the omp-web server's
 * command allowlist answers "Unsupported command: goal". Both mean: fall back
 * to a plain prompt so the user's aim is never lost.
 */
fun isUnknownGoalCommand(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    if (message.isEmpty()) return false
    val mentionsGoal = Regex("(?i)command\\s*:?\\s*goal\\b").containsMatchIn(message)
    return mentionsGoal && Regex("(?i)unknown|unsupported").containsMatchIn(message)
}

// -------------------------------------------------------------- objective

private val GOAL_SECTION_LINE = Regex(
    "^(no-gos?|stop conditions?)\\s*:\\s*(.*)$",
    setOf(RegexOption.IGNORE_CASE),
)

/**
 * Split `/goal` arguments into the aim and the optional user sections.
 *
 * Lines after the aim that start with `No-gos:` or `Stop conditions:` belong
 * to that section (inline text and the following lines) until the next marker.
 */
internal data class GoalArguments(
    val aim: String,
    val noGos: String,
    val stopConditions: String,
)

internal fun splitGoalArguments(args: String): GoalArguments {
    val aim = StringBuilder()
    val noGos = StringBuilder()
    val stopConditions = StringBuilder()
    var section = 0
    for (line in args.lines()) {
        val match = GOAL_SECTION_LINE.matchEntire(line.trim())
        if (match != null) {
            section = if (match.groupValues[1].startsWith("no-go", ignoreCase = true)) 1 else 2
            val inline = match.groupValues[2].trim()
            if (inline.isNotEmpty()) {
                val target = if (section == 1) noGos else stopConditions
                if (target.isNotEmpty()) target.append('\n')
                target.append(inline)
            }
            continue
        }
        when (section) {
            0 -> aim.appendLine(line)
            1 -> noGos.appendLine(line)
            else -> stopConditions.appendLine(line)
        }
    }
    val parsedAim = aim.toString().trim()
    return GoalArguments(
        // No marker at all: the full text is the aim.
        aim = parsedAim.ifEmpty { args.trim() },
        noGos = noGos.toString().trim(),
        stopConditions = stopConditions.toString().trim(),
    )
}

/**
 * The goal objective sent to omp (and the fallback prompt for servers without
 * goal mode). It forces a plan with key points, no-gos and objectively
 * verifiable stop conditions, and completion only after every condition is
 * proven.
 */
fun buildGoalObjective(args: String): String {
    val parsed = splitGoalArguments(args)
    return buildString {
        appendLine("Objective:")
        appendLine()
        appendLine(parsed.aim)
        appendLine()
        appendLine("Before doing any work, write a short plan with these sections:")
        appendLine("- Key points: the concrete deliverables and the steps to reach them.")
        appendLine("- No-gos: what you must not do.")
        appendLine(
            "- Stop conditions: each condition must be objectively verifiable with concrete evidence " +
                "(command output, test result, file contents).",
        )
        if (parsed.noGos.isNotEmpty()) {
            appendLine()
            appendLine("Additional no-gos from the user:")
            appendLine(parsed.noGos)
        }
        if (parsed.stopConditions.isNotEmpty()) {
            appendLine()
            appendLine("Additional stop conditions from the user:")
            appendLine(parsed.stopConditions)
        }
        appendLine()
        appendLine("Keep the plan as todos and update them as you work. Work autonomously until every stop " +
            "condition is verified with evidence. If something blocks you, explain it and keep working on " +
            "the reachable parts; never redefine success as a smaller subset. Only call the goal tool with " +
            "op \"complete\" after every stop condition is proven with evidence — if one cannot be proven, " +
            "keep going and state exactly what is missing.")
    }.trimEnd()
}

/** `8s`, `5m`, `2h 3m` — the banner's goal time. */
fun formatGoalElapsed(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    if (total < 60) return "${total}s"
    val minutes = total / 60
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}

/** Compact token counts for the goal banner (`1.2k`, `3.4M`). */
fun formatGoalTokens(tokens: Long): String {
    val value = tokens.coerceAtLeast(0)
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000.0)
        else -> value.toString()
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull
