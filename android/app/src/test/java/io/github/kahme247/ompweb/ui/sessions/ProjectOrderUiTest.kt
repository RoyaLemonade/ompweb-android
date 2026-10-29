package io.github.kahme247.ompweb.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.kahme247.ompweb.events.ConnectionState
import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import io.github.kahme247.ompweb.screenshot.ScreenshotMatrix
import io.github.kahme247.ompweb.ui.theme.OmpWebTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pane renders the activity order from the state: pinned projects first
 * (in pin order), then projects with a running chat, then the newest session.
 * The frozen-order decision itself is covered by `ProjectOrderHoldTest`; these
 * tests prove the pane actually uses the order it computes and which
 * interactions open its gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h890dp-mdpi", application = android.app.Application::class)
class ProjectOrderUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `pinned first, then running, then newest activity`() {
        render(
            state = SessionsUiState(
                loading = false,
                projectsLoaded = true,
                connection = ConnectionState.Connected,
                projects = listOf(
                    ManagedProject("/home/dev/alpha"),
                    ManagedProject("/home/dev/beta"),
                    ManagedProject("/home/dev/gamma"),
                ),
                sessions = listOf(
                    session("s-alpha", "/home/dev/alpha", "2026-01-01T10:00:00.000Z"),
                    session("s-beta", "/home/dev/beta", "2026-03-01T10:00:00.000Z"),
                    session("s-gamma", "/home/dev/gamma", "2026-02-01T10:00:00.000Z"),
                ),
                runningIds = setOf("s-gamma"),
                // alpha leads although its session is the oldest.
                pinnedProjectPaths = listOf("/home/dev/alpha"),
                expandedProjects = emptySet(),
                home = "/home/dev",
            ),
        )

        val alpha = topOf("alpha")
        val gamma = topOf("gamma")
        val beta = topOf("beta")
        assertTrue("pinned alpha must lead, got $alpha / $gamma / $beta", alpha < gamma)
        assertTrue("running gamma must precede idle beta", gamma < beta)
    }

    @Test
    fun `a pinned project row carries a visible pin with its own label`() {
        render(
            state = SessionsUiState(
                loading = false,
                projectsLoaded = true,
                connection = ConnectionState.Connected,
                projects = listOf(
                    ManagedProject("/home/dev/alpha"),
                    ManagedProject("/home/dev/beta"),
                ),
                sessions = emptyList(),
                pinnedProjectPaths = listOf("/home/dev/alpha"),
                expandedProjects = emptySet(),
                home = "/home/dev",
            ),
        )

        // The header used to show the pin state only inside the menu, so a
        // screen reader could never tell why alpha sits at the top.
        composeRule.onAllNodesWithContentDescription("Pinned project", useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun `an open search panel with an empty query does not freeze the order`() {
        val state = renderState(twoProjects(betaNewest = true).copy(searchOpen = true))

        assertTrue("beta leads on activity", topOf("beta") < topOf("alpha"))

        composeRule.runOnIdle { state.value = twoProjects(betaNewest = false).copy(searchOpen = true) }
        composeRule.waitForIdle()

        // Panel visibility is not an interaction: an untouched, empty field
        // shows the whole list, so the order follows at once instead of
        // staying frozen until the panel is closed.
        assertTrue("alpha takes over", topOf("alpha") < topOf("beta"))
    }

    @Test
    fun `a gesture whose list disappears does not freeze the order`() {
        var clock = 0L
        val state = renderState(twoProjects(betaNewest = true)) { clock }

        assertTrue("beta leads on activity", topOf("beta") < topOf("alpha"))

        // A finger on the list opens the order gate: even a new lead does not
        // move the rows under it.
        composeRule.onNodeWithText("beta").performTouchInput { down(center) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.value = twoProjects(betaNewest = false) }
        composeRule.waitForIdle()
        assertTrue(
            "a finger on the list holds the order",
            topOf("beta") < topOf("alpha"),
        )

        // "Running only" empties the list, so the LazyColumn leaves the
        // composition mid-gesture and Compose cancels the gesture coroutine.
        composeRule.runOnIdle { state.value = state.value.copy(runningOnly = true) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { state.value = twoProjects(betaNewest = false) }
        composeRule.waitForIdle()
        assertTrue(
            "the settle window still holds the old order",
            topOf("beta") < topOf("alpha"),
        )

        // Both clocks move past the window: the order is adopted instead of
        // staying frozen until the screen is left.
        clock = 1_500
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()

        assertTrue(
            "a cancelled gesture must not leave the order frozen",
            topOf("alpha") < topOf("beta"),
        )
    }

    private fun twoProjects(betaNewest: Boolean) = SessionsUiState(
        loading = false,
        projectsLoaded = true,
        connection = ConnectionState.Connected,
        projects = listOf(
            ManagedProject("/home/dev/alpha"),
            ManagedProject("/home/dev/beta"),
        ),
        sessions = listOf(
            session("s-alpha", "/home/dev/alpha", if (betaNewest) OLDER else NEWER),
            session("s-beta", "/home/dev/beta", if (betaNewest) NEWER else OLDER),
        ),
        expandedProjects = emptySet(),
        home = "/home/dev",
    )

    private fun topOf(label: String): Float =
        composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top

    private fun session(id: String, cwd: String, modified: String) = SessionSummary(
        id = id,
        cwd = cwd,
        modified = modified,
        messageCount = 1,
    )

    private fun render(state: SessionsUiState) {
        renderState(state)
    }

    /** Renders the pane from a mutable state so a test can change it later. */
    private fun renderState(
        initial: SessionsUiState,
        interactionClock: () -> Long = { System.currentTimeMillis() },
    ): MutableState<SessionsUiState> {
        val state = mutableStateOf(initial)
        composeRule.setContent {
            OmpWebTheme(themeId = ScreenshotMatrix.LIGHT_THEME_ID) {
                Box(modifier = Modifier.size(411.dp, 890.dp)) {
                    SessionListPane(
                        state = state.value,
                        historyCanBack = false,
                        historyCanForward = false,
                        themeId = ScreenshotMatrix.LIGHT_THEME_ID,
                        onRefresh = {},
                        onRetryConnection = {},
                        onThemeSelected = {},
                        onSignOut = {},
                        onChangeServer = {},
                        notificationBell = {},
                        onOpenSettings = {},
                        onBack = {},
                        onForward = {},
                        onQuickSwitcher = {},
                        onSearchToggle = {},
                        onSearchQueryChange = {},
                        onSearchClose = {},
                        onSearchModeChange = {},
                        onOpenMessageHit = {},
                        onLoadMoreMessageResults = {},
                        onRetryMessageSearch = {},
                        onRunningOnlyToggle = {},
                        onNewSession = {},
                        onAddProject = {},
                        onOpenFiles = {},
                        onImport = {},
                        onOpenArchive = {},
                        onOpenSession = {},
                        onTogglePin = {},
                        onToggleProject = {},
                        onActivateProject = {},
                        onNewSessionInProject = {},
                        onRenameProject = {},
                        onLaunchConfig = {},
                        onHideProject = {},
                        onToggleProjectPin = {},
                        onRenameSession = {},
                        onArchiveSession = {},
                        onAutoNameSession = {},
                        onCopyTranscript = {},
                        onExportSession = {},
                        onDeleteSession = {},
                        onCopyId = {},
                        interactionClock = interactionClock,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        return state
    }

    private companion object {
        const val NEWER = "2026-04-01T10:00:00.000Z"
        const val OLDER = "2026-01-01T10:00:00.000Z"
    }
}
