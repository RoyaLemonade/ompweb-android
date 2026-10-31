package io.github.kahme247.ompweb.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The goal banner is fed by omp, never by a client-side copy: `goal_updated`
 * and `get_state.goal` are the only sources, and a dropped/completed goal
 * disappears while a paused one stays for Resume.
 */
class ChatEngineGoalTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    private fun engine(): ChatEngine = ChatEngine(now = { 1_800_000_000_000L })

    private fun updated(status: String, objective: String = "ship it"): JsonObject = obj(
        """
        {"type":"goal_updated",
         "goal":{"id":"g1","objective":"$objective","status":"$status","tokensUsed":12,"timeUsedSeconds":8},
         "state":{"enabled":true,"mode":"active"}}
        """.trimIndent(),
    )

    private fun getState(goal: String?): JsonObject = obj(
        """{"running":false,"state":{"isStreaming":false,"isPromptRunning":false,"goal":$goal}}""",
    )

    @Test
    fun `a live goal_updated paints the active goal`() {
        val engine = engine()

        engine.onFrame(updated("active"))
        engine.flush()

        val goal = engine.state.value.goal!!
        assertEquals("g1", goal.id)
        assertEquals("ship it", goal.objective)
        assertEquals("active", goal.status)
        assertEquals(12L, goal.tokensUsed)
        assertEquals(8L, goal.timeUsedSeconds)
    }

    @Test
    fun `an aborted run keeps the goal visible as paused`() {
        val engine = engine()

        engine.onFrame(updated("active"))
        engine.flush()
        engine.onFrame(updated("paused"))
        engine.flush()

        assertEquals("paused", engine.state.value.goal!!.status)
        assertTrue(engine.state.value.goal!!.paused)
    }

    @Test
    fun `dropped and completed goals clear the banner`() {
        val engine = engine()

        engine.onFrame(updated("active"))
        engine.flush()
        engine.onFrame(updated("dropped"))
        engine.flush()
        assertNull(engine.state.value.goal)

        engine.onFrame(updated("active"))
        engine.flush()
        engine.onFrame(updated("completed"))
        engine.flush()
        assertNull(engine.state.value.goal)
    }

    @Test
    fun `a null goal_updated clears the banner`() {
        val engine = engine()

        engine.onFrame(updated("active"))
        engine.flush()
        engine.onFrame(obj("""{"type":"goal_updated","goal":null,"state":null}"""))
        engine.flush()

        assertNull(engine.state.value.goal)
    }

    @Test
    fun `get_state fills the goal and an explicit null clears it`() {
        val engine = engine()

        engine.applyAgentState(
            getState("""{"id":"g1","objective":"ship it","status":"active","tokenBudget":5000,"tokensUsed":10}"""),
        )
        engine.flush()

        val goal = engine.state.value.goal!!
        assertEquals("ship it", goal.objective)
        assertEquals(5000L, goal.tokenBudget)

        engine.applyAgentState(getState("null"))
        engine.flush()

        assertNull(engine.state.value.goal)
    }

    @Test
    fun `an older snapshot without a goal field keeps the known goal`() {
        val engine = engine()

        engine.applyAgentState(
            getState("""{"id":"g1","objective":"ship it","status":"active"}"""),
        )
        engine.flush()
        engine.applyAgentState(
            obj("""{"running":false,"state":{"isStreaming":false,"isPromptRunning":false}}"""),
        )
        engine.flush()

        assertEquals("ship it", engine.state.value.goal!!.objective)
    }

    @Test
    fun `a goal command reply applies the same state`() {
        val engine = engine()

        engine.applyGoalResponse(
            obj(
                """{"goal":{"id":"g1","objective":"ship it","status":"active"},
                    "state":{"enabled":true,"mode":"active"}}""",
            ),
        )
        engine.flush()

        assertEquals("ship it", engine.state.value.goal!!.objective)

        engine.applyGoalResponse(obj("""{"goal":null,"state":null}"""))
        engine.flush()
        assertNull(engine.state.value.goal)
    }
}
