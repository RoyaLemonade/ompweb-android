package io.github.kahme247.ompweb.ui.sessions

import io.github.kahme247.ompweb.net.ExitedSession
import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlaceholdersTest {

    @Test
    fun `a running session without a file still gets a row`() {
        val display = withLivePlaceholders(
            sessions = emptyList(),
            runningIds = setOf("fresh"),
            runningCwds = mapOf("fresh" to "/work/app"),
            exited = emptyMap(),
            projects = listOf(ManagedProject(path = "/work/app")),
            activeCwd = null,
        )

        assertEquals(listOf("fresh"), display.sessions.map { it.id })
        assertEquals("/work/app", display.sessions.single().cwd)
        assertEquals("/work/app", display.sessions.single().projectRoot)
    }

    @Test
    fun `an exited session keeps the directory the server reported`() {
        val display = withLivePlaceholders(
            sessions = emptyList(),
            runningIds = emptySet(),
            runningCwds = emptyMap(),
            exited = mapOf("gone" to ExitedSession(id = "gone", cwd = "/work/other")),
            projects = emptyList(),
            activeCwd = "/work/active",
        )

        assertEquals("/work/other", display.sessions.single().cwd)
        assertEquals("/work/other", display.sessions.single().projectRoot)
    }

    @Test
    fun `sessions that are on disk are not duplicated and unregistered workspaces become projects`() {
        val existing = SessionSummary(id = "known", cwd = "/work/app")
        val display = withLivePlaceholders(
            sessions = listOf(existing),
            runningIds = setOf("known", "fresh"),
            runningCwds = mapOf("known" to "/work/app", "fresh" to "/work/lonely"),
            exited = emptyMap(),
            projects = listOf(ManagedProject(path = "/work/app")),
            activeCwd = null,
        )

        assertEquals(listOf("known", "fresh"), display.sessions.map { it.id })
        val projects = withPlaceholderProjects(listOf(ManagedProject(path = "/work/app")), display)
        assertEquals(listOf("/work/app", "/work/lonely"), projects.map { it.path })
    }

    @Test
    fun `a hidden project does not come back from its still-listed sessions`() {
        // Hiding only marks the registry entry: `GET /api/projects` no longer
        // lists the project, but `GET /api/sessions` keeps returning its
        // sessions. The project list is the server's, so nothing may be derived
        // from a file-backed session — otherwise the row the user just hid
        // reappears from the sessions hiding deliberately leaves on disk.
        val sessions = listOf(
            SessionSummary(
                id = "s1",
                path = "/home/u/.omp/sessions/s1.jsonl",
                cwd = "C:\\Work\\Hidden",
                projectRoot = "C:\\Work\\Hidden",
                projectKey = "c:\\work\\hidden",
            ),
        )
        val display = withLivePlaceholders(
            sessions = sessions,
            runningIds = emptySet(),
            runningCwds = emptyMap(),
            exited = emptyMap(),
            projects = emptyList(),
            activeCwd = null,
        )

        val projects = withPlaceholderProjects(emptyList(), display)

        assertTrue("a file-backed session is not a placeholder", display.placeholders.isEmpty())
        assertEquals(emptyList<ManagedProject>(), projects)
    }

    @Test
    fun `a live session without any directory falls back to the active workspace`() {
        val display = withLivePlaceholders(
            sessions = emptyList(),
            runningIds = setOf("bare"),
            runningCwds = emptyMap(),
            exited = emptyMap(),
            projects = emptyList(),
            activeCwd = "/work/active",
        )

        assertEquals("/work/active", display.sessions.single().cwd)
    }

    @Test
    fun `a session without a directory has no workspace key`() {
        val display = withLivePlaceholders(
            sessions = emptyList(),
            runningIds = setOf("bare"),
            runningCwds = emptyMap(),
            exited = emptyMap(),
            projects = emptyList(),
            activeCwd = null,
        )

        assertNull(display.sessions.single().projectRoot)
    }
}
