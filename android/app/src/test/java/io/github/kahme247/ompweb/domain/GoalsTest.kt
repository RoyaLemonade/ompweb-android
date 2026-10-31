package io.github.kahme247.ompweb.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** omp's goal payload: objective template, state parsing and fallback detection. */
class GoalsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    // ------------------------------------------------------------ template

    @Test
    fun `the objective forces a plan with key points, no-gos and stop conditions`() {
        val objective = buildGoalObjective("ship the export")

        assertTrue(objective.contains("ship the export"))
        assertTrue(objective.contains("Key points"))
        assertTrue(objective.contains("No-gos"))
        assertTrue(objective.contains("Stop conditions"))
        assertTrue("the agent may only complete after proof", objective.contains("goal tool"))
        assertTrue(objective.contains("evidence"))
        assertTrue(objective.contains("redefine success as a smaller"))
    }

    @Test
    fun `optional user sections ride along and leave the aim intact`() {
        val objective = buildGoalObjective(
            """
            ship the export
            No-gos:
            - never touch the production database
            - no force push
            Stop conditions:
            - the full test suite passes
            - the release APK checksum is recorded
            """.trimIndent(),
        )

        assertTrue("the aim stays the objective", objective.contains("ship the export"))
        assertTrue(objective.contains("never touch the production database"))
        assertTrue(objective.contains("no force push"))
        assertTrue(objective.contains("the full test suite passes"))
        assertTrue(objective.contains("the release APK checksum is recorded"))
        assertFalse("the markers are not part of the aim", objective.contains("ship the export\nNo-gos"))
    }

    @Test
    fun `an objective without markers is used as-is`() {
        val objective = buildGoalObjective("  make the build green  ")
        assertTrue(objective.contains("make the build green"))
        assertFalse(objective.contains("Additional no-gos"))
    }

    @Test
    fun `elapsed time and token counts stay compact`() {
        assertEquals("0s", formatGoalElapsed(0))
        assertEquals("59s", formatGoalElapsed(59))
        assertEquals("5m", formatGoalElapsed(5 * 60))
        assertEquals("2h 3m", formatGoalElapsed((2 * 3600) + (3 * 60)))
        assertEquals("999", formatGoalTokens(999))
        assertEquals("1.2k", formatGoalTokens(1_234))
        assertEquals("3.4M", formatGoalTokens(3_400_000))
    }

    // --------------------------------------------------------------- state

    @Test
    fun `a goal object parses every field get_state carries`() {
        val goal = parseGoal(
            obj(
                """
                {"id":"abc","objective":"ship it","status":"active","tokenBudget":5000,
                 "tokensUsed":1234,"timeUsedSeconds":42,"createdAt":100,"updatedAt":200}
                """.trimIndent(),
            ),
        )

        assertEquals(
            GoalState(
                id = "abc",
                objective = "ship it",
                status = "active",
                tokenBudget = 5000,
                tokensUsed = 1234,
                timeUsedSeconds = 42,
                createdAt = 100,
                updatedAt = 200,
            ),
            goal,
        )
        assertTrue(goal!!.visible)
        assertFalse(goal.paused)
    }

    @Test
    fun `a paused goal stays visible and has no budget when omp reports none`() {
        val goal = parseGoal(obj("""{"id":"abc","objective":"ship it","status":"paused"}"""))!!

        assertEquals("paused", goal.status)
        assertTrue(goal.visible)
        assertTrue(goal.paused)
        assertNull(goal.tokenBudget)
    }

    @Test
    fun `dropped completed and malformed goals are never shown`() {
        assertEquals("dropped", parseGoal(obj("""{"id":"a","objective":"x","status":"dropped"}"""))!!.status)
        assertFalse(parseGoal(obj("""{"id":"a","objective":"x","status":"completed"}"""))!!.visible)
        assertNull(parseGoal(null))
        assertNull(parseGoal(obj("""{"objective":"no id","status":"active"}""")))
        assertNull(parseGoal(obj("""{"id":"a","status":"active"}""")))
        assertNull(parseGoal(obj("""{"id":"a","objective":"x"}""")))
    }

    @Test
    fun `a command reply exposes data goal`() {
        val data = obj(
            """{"goal":{"id":"g","objective":"do it","status":"active","tokensUsed":3},
                "state":{"enabled":true,"mode":"active"}}""",
        )
        assertEquals("do it", parseGoalFromCommandData(data)!!.objective)
        assertNull(parseGoalFromCommandData(obj("""{"goal":null,"state":null}""")))
        assertNull(parseGoalFromCommandData(null))
    }

    // ------------------------------------------------------------ fallback

    @Test
    fun `unknown and unsupported goal commands trigger the prompt fallback`() {
        assertTrue(isUnknownGoalCommand(IllegalStateException("Unknown command: goal")))
        assertTrue(
            isUnknownGoalCommand(
                IllegalStateException("Error: Unsupported command: goal"),
            ),
        )
    }

    @Test
    fun `other goal failures do not silently become prompts`() {
        assertFalse(isUnknownGoalCommand(IllegalStateException("goal mode is disabled")))
        assertFalse(isUnknownGoalCommand(IllegalStateException("A goal is already active")))
        assertFalse(isUnknownGoalCommand(java.io.IOException("no connection")))
    }
}
