package io.github.kahme247.ompweb.domain

import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports `lib/project-ordering.test.mjs` case by case. */
class ProjectOrderingTest {

    private fun session(id: String, overrides: SessionSummary.() -> SessionSummary = { this }) =
        SessionSummary(
            id = id,
            path = "/sessions/$id.jsonl",
            cwd = "/work/$id",
            created = "2026-01-01T00:00:00.000Z",
            modified = "2026-01-01T00:00:00.000Z",
            messageCount = 1,
            firstMessage = "hi",
        ).overrides()

    @Test
    fun `pinned projects lead in pin order, then running ones, then activity`() {
        val projects = listOf(
            ManagedProject(path = "/proj/a"),
            ManagedProject(path = "/proj/b"),
            ManagedProject(path = "/proj/c"),
        )
        val ordered = orderProjectsByActivity(
            projects = projects,
            pinnedPaths = listOf("/proj/c", "/proj/a"),
            runningProjects = listOf("/proj/b"),
            lastActivity = mapOf(
                comparableProjectPath("/proj/a") to 1L,
                comparableProjectPath("/proj/b") to 9L,
                comparableProjectPath("/proj/c") to 5L,
            ),
        )
        assertEquals(listOf("/proj/c", "/proj/a", "/proj/b"), ordered.map { it.path })
    }

    @Test
    fun `a newly pinned project leads the pinned group`() {
        val projects = listOf(
            ManagedProject(path = "/proj/a"),
            ManagedProject(path = "/proj/b"),
            ManagedProject(path = "/proj/c"),
        )
        val afterPinning = orderProjectsByActivity(
            projects = projects,
            // toggleProjectPin puts the newest pin first.
            pinnedPaths = listOf("/proj/b", "/proj/a"),
            runningProjects = listOf("/proj/c"),
            lastActivity = mapOf(comparableProjectPath("/proj/c") to 9_000L),
        )
        assertEquals(listOf("/proj/b", "/proj/a", "/proj/c"), afterPinning.map { it.path })
    }

    @Test
    fun `running projects come before idle ones, newest session activity leads the rest`() {
        val projects = listOf(
            ManagedProject(path = "/proj/old-idle"),
            ManagedProject(path = "/proj/running-old"),
            ManagedProject(path = "/proj/new-idle"),
            ManagedProject(path = "/proj/no-sessions"),
        )
        val ordered = orderProjectsByActivity(
            projects = projects,
            pinnedPaths = emptyList(),
            runningProjects = listOf("/proj/running-old"),
            lastActivity = mapOf(
                comparableProjectPath("/proj/old-idle") to 100L,
                comparableProjectPath("/proj/running-old") to 1L,
                comparableProjectPath("/proj/new-idle") to 200L,
            ),
        )
        assertEquals(
            listOf("/proj/running-old", "/proj/new-idle", "/proj/old-idle", "/proj/no-sessions"),
            ordered.map { it.path },
        )
    }

    @Test
    fun `equal activity keeps the incoming order`() {
        val projects = listOf(
            ManagedProject(path = "/proj/first"),
            ManagedProject(path = "/proj/second"),
            ManagedProject(path = "/proj/third"),
        )
        val activity = 42L
        val ordered = orderProjectsByActivity(
            projects = projects,
            pinnedPaths = emptyList(),
            runningProjects = listOf("/proj/second", "/proj/third"),
            lastActivity = projects.associate { comparableProjectPath(it.path) to activity },
        )
        assertEquals(listOf("/proj/second", "/proj/third", "/proj/first"), ordered.map { it.path })
    }

    @Test
    fun `pins match through casing-only path differences`() {
        val projects = listOf(
            ManagedProject(path = "D:\\OtherProjects\\Waku"),
            ManagedProject(path = "D:\\OtherProjects\\Other"),
        )
        val ordered = orderProjectsByActivity(
            projects = projects,
            pinnedPaths = listOf("d:\\otherprojects\\waku"),
            runningProjects = emptyList(),
            lastActivity = emptyMap(),
        )
        assertEquals(listOf("D:\\OtherProjects\\Waku", "D:\\OtherProjects\\Other"), ordered.map { it.path })
    }

    @Test
    fun `projectLastActivity keeps the newest timestamp and ignores malformed values`() {
        val sessions = listOf(
            session("a-old") { copy(projectRoot = "/proj/a", modified = "2026-01-01T00:00:00.000Z") },
            session("a-new") { copy(projectRoot = "/proj/a", modified = "2026-03-01T00:00:00.000Z") },
            session("a-broken") { copy(projectRoot = "/proj/a", modified = "not-a-date") },
            session("b") { copy(projectRoot = "/proj/b", modified = "2026-02-01T00:00:00.000Z") },
            session("c-broken") { copy(projectRoot = "/proj/c", modified = "") },
        )
        val activity = projectLastActivity(sessions)
        assertEquals(
            java.time.Instant.parse("2026-03-01T00:00:00.000Z").toEpochMilli(),
            activity.getValue(comparableProjectPath("/proj/a")),
        )
        assertEquals(
            java.time.Instant.parse("2026-02-01T00:00:00.000Z").toEpochMilli(),
            activity.getValue(comparableProjectPath("/proj/b")),
        )
        assertFalse(activity.containsKey(comparableProjectPath("/proj/c")))
    }

    @Test
    fun `applyProjectOrder keeps the frozen order and appends unknown projects`() {
        val projects = listOf(
            ManagedProject(path = "/proj/new"),
            ManagedProject(path = "/proj/a"),
            ManagedProject(path = "/proj/b"),
        )
        val ordered = applyProjectOrder(projects, listOf("/proj/b", "/proj/a"))
        assertEquals(listOf("/proj/b", "/proj/a", "/proj/new"), ordered.map { it.path })
        assertEquals(projects, applyProjectOrder(projects, emptyList()))
    }

    @Test
    fun `groups sessions under their project, including worktree sessions`() {
        val projects = listOf(
            ManagedProject(path = "/repo", addedAt = "2026-01-01T00:00:00.000Z"),
            ManagedProject(path = "/empty", addedAt = "2026-02-01T00:00:00.000Z"),
            ManagedProject(path = "/other"),
        )
        val sessions = listOf(
            session("wt") { copy(cwd = "/repo-worktrees/feature", projectRoot = "/repo") },
            session("fork") { copy(parentSessionId = "parent", projectRoot = "/other") },
            session("parent") { copy(projectRoot = "/other") },
        )
        val grouped = groupSessionsByProject(projects, sessions)
        assertEquals(listOf("wt"), grouped.getValue("/repo").map { it.id })
        assertEquals(emptyList<SessionSummary>(), grouped.getValue("/empty"))
        assertEquals(
            listOf("fork", "parent"),
            grouped.getValue("/other").map { it.id }.sorted(),
        )
    }

    @Test
    fun `projectActivityCounts tallies running, unread, and exited sessions per project`() {
        val sessions = listOf(
            session("running-main") { copy(projectRoot = "/repo") },
            session("unread-main") { copy(projectRoot = "/repo") },
            session("exited-main") { copy(projectRoot = "/repo") },
            session("running-wt") { copy(cwd = "/repo-worktrees/x", projectRoot = "/repo") },
            session("idle-other") { copy(projectRoot = "/other") },
        )
        val counts = projectActivityCounts(
            sessions,
            listOf("running-main", "running-wt"),
            listOf("unread-main"),
            listOf("exited-main"),
        )
        assertEquals(
            ProjectActivity(running = 2, unread = 1, exited = 1),
            counts.getValue(comparableProjectPath("/repo")),
        )
        assertEquals(ProjectActivity(), counts.getValue(comparableProjectPath("/other")))
    }

    @Test
    fun `casing-only projectRoot differences still group and tally on Windows`() {
        val projects = listOf(ManagedProject(path = "D:\\OtherProjects\\Waku", addedAt = "2026-01-01T00:00:00.000Z"))
        val sessions = listOf(session("s1") { copy(projectRoot = "d:\\otherprojects\\waku") })
        val grouped = groupSessionsByProject(projects, sessions)
        assertEquals(listOf("s1"), grouped.getValue("D:\\OtherProjects\\Waku").map { it.id })
        val counts = projectActivityCounts(sessions, listOf("s1"), emptyList())
        assertEquals(
            ProjectActivity(running = 1),
            counts.getValue(comparableProjectPath("d:\\otherprojects\\waku")),
        )
    }

    @Test
    fun `running session placeholders group stably under their own project cwd, not active workspace`() {
        val projects = listOf(
            ManagedProject(path = "/project-a", addedAt = "2026-01-01T00:00:00.000Z"),
            ManagedProject(path = "/project-b", addedAt = "2026-02-01T00:00:00.000Z"),
            ManagedProject(path = "/project-c", addedAt = "2026-03-01T00:00:00.000Z"),
        )
        val visible = listOf(
            session("session-b") { copy(projectRoot = "/project-b") },
            session("session-c") { copy(projectRoot = "/project-c") },
            session("running-in-a") { copy(path = "", cwd = "/project-a", projectRoot = "/project-a") },
        )
        val grouped = groupSessionsByProject(projects, visible)
        assertEquals(listOf("running-in-a"), grouped.getValue("/project-a").map { it.id })
        assertEquals(listOf("session-b"), grouped.getValue("/project-b").map { it.id })
        assertEquals(listOf("session-c"), grouped.getValue("/project-c").map { it.id })
    }

    @Test
    fun `workspaceKeyOf prefers projectKey then projectRoot then cwd`() {
        assertEquals("key", workspaceKeyOf(session("a") { copy(projectKey = "key", projectRoot = "root", cwd = "cwd") }))
        assertEquals("root", workspaceKeyOf(session("a") { copy(projectRoot = "root", cwd = "cwd") }))
        assertEquals("cwd", workspaceKeyOf(session("a") { copy(cwd = "cwd") }))
        assertTrue(comparableProjectPath("C:/Repo") == comparableProjectPath("c:\\repo\\"))
    }
}
