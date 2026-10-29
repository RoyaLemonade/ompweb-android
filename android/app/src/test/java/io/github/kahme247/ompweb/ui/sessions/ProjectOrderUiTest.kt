package io.github.kahme247.ompweb.ui.sessions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * The frozen-order decision itself is covered by `ProjectOrderHoldTest`; this
 * test proves the pane actually uses the order it computes.
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

    private fun topOf(label: String): Float =
        composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.top

    private fun session(id: String, cwd: String, modified: String) = SessionSummary(
        id = id,
        cwd = cwd,
        modified = modified,
        messageCount = 1,
    )

    private fun render(state: SessionsUiState) {
        composeRule.setContent {
            OmpWebTheme(themeId = ScreenshotMatrix.LIGHT_THEME_ID) {
                Box(modifier = Modifier.size(411.dp, 890.dp)) {
                    SessionListPane(
                        state = state,
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
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
