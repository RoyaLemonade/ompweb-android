package io.github.kahme247.ompweb.ui.worktrees

import io.github.kahme247.ompweb.net.WorktreeEntry
import io.github.kahme247.ompweb.net.WorktreeList
import io.github.kahme247.ompweb.net.WorktreesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one cache shared by the Files panel and the new-session select: an
 * answer that arrives while a fetch is in flight must not overwrite a newer
 * list another surface stored (1.0.21 flaked when the pane's empty fetch
 * landed after the panel had created a worktree).
 */
class WorktreeCacheTest {

    private class GatedWorktreesApi : WorktreesApi {
        /** Completed when the pane's fetch reached the route. */
        val requested = CompletableDeferred<Unit>()

        /** The (stale) answer the route sends once the test allows it. */
        val answer = CompletableDeferred<WorktreeList>()

        override suspend fun list(cwd: String): WorktreeList {
            requested.complete(Unit)
            return answer.await()
        }

        override suspend fun create(cwd: String, branch: String): WorktreeEntry =
            error("not used in this test")

        override suspend fun remove(cwd: String, path: String, force: Boolean) = Unit
    }

    @Test
    fun `a list stored while a fetch is in flight is not overwritten by the stale answer`() = runTest {
        val api = GatedWorktreesApi()
        val cache = WorktreeCache(api)
        val main = WorktreeEntry("/repo", branch = "main", isMain = true)
        val stale = WorktreeList(
            projectRoot = "/repo",
            isGit = true,
            isTopLevel = true,
            worktrees = listOf(main),
        )
        val fresh = stale.copy(worktrees = listOf(main, WorktreeEntry("/repo-worktrees/feat", branch = "feat")))

        val fetch = async { cache.list("/repo") }
        api.requested.await()
        // The Files panel creates a worktree and stores the answer first.
        cache.put("/repo", fresh)
        // The fetch that started earlier now returns its stale list.
        api.answer.complete(stale)

        assertEquals("the newer panel list is what the fetch reports", fresh, fetch.await())
        assertEquals("the stale answer must not replace it", fresh, cache.cached("/repo"))
    }
}
