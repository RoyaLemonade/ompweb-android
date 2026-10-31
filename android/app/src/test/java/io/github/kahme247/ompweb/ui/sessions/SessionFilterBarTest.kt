package io.github.kahme247.ompweb.ui.sessions

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.domain.SessionAgeFilter
import io.github.kahme247.ompweb.events.ConnectionState
import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import io.github.kahme247.ompweb.screenshot.ScreenshotMatrix
import io.github.kahme247.ompweb.ui.theme.OmpWebTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filter bar in the sessions list: the three chips, the age menu, the
 * hidden-count row with its clear action, and the empty state when the filters
 * hide everything. Pinned rows stay visible, because a pin is the user's
 * explicit choice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h890dp-mdpi", application = android.app.Application::class)
class SessionFilterBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the bar shows Running, Unread and the age chip and reports taps`() {
        var unreadToggled = false
        var runningToggled = false
        setContent(
            state = state(),
            onRunningOnlyToggle = { runningToggled = true },
            onUnreadOnlyToggle = { unreadToggled = true },
        )

        composeRule.onNodeWithTag(SESSION_FILTER_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_FILTER_RUNNING_TAG)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(SESSION_FILTER_UNREAD_TAG)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(SESSION_FILTER_AGE_TAG)
            .assertIsDisplayed()
            .assertTextEquals("Age: ${context.getString(R.string.sessions_filter_age_all)}")

        assertTrue("Running chip reports its tap", runningToggled)
        assertTrue("Unread chip reports its tap", unreadToggled)
    }

    @Test
    fun `the age menu selects one preset`() {
        var selected: SessionAgeFilter? = null
        setContent(state = state(), onAgeFilterChange = { selected = it })

        composeRule.onNodeWithTag(SESSION_FILTER_AGE_TAG).performClick()
        composeRule.onNodeWithText(context.getString(R.string.sessions_filter_age_two_days)).performClick()

        assertEquals(SessionAgeFilter.TwoDays, selected)
    }

    @Test
    fun `the hidden-count row says how many rows the chips hide and clears them`() {
        var cleared = false
        setContent(
            state = state(
                sessions = listOf(
                    session("unread", name = "Needs attention"),
                    session("read", name = "Already read"),
                ),
                unreadIds = setOf("unread"),
                unreadOnly = true,
            ),
            onClearFilters = { cleared = true },
        )

        composeRule.onNodeWithText("Needs attention").assertIsDisplayed()
        composeRule.onNodeWithText("Already read").assertDoesNotExist()
        composeRule.onNodeWithTag(SESSION_FILTER_HIDDEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("1 hidden by filters").assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.sessions_filter_clear)).performClick()
        assertTrue("the hidden row's Clear reports the tap", cleared)
    }

    @Test
    fun `filters that hide everything show the empty state with a clear action`() {
        var cleared = false
        setContent(
            state = state(
                sessions = listOf(session("read", name = "Already read")),
                unreadOnly = true,
            ),
            onClearFilters = { cleared = true },
        )

        composeRule.onNodeWithText(context.getString(R.string.home_no_matches)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.sessions_filter_clear)).performClick()
        assertTrue("Clear filters reports the tap", cleared)
    }

    @Test
    fun `an unread notification and a waiting input dialog count as unread`() {
        setContent(
            state = state(
                sessions = listOf(
                    session("notified", name = "Notified chat"),
                    session("dialog", name = "Dialog chat"),
                    session("read", name = "Already read"),
                ),
                unreadOnly = true,
            ),
            notificationUnreadSessionIds = setOf("notified"),
            awaitingInputSessionIds = setOf("dialog"),
        )

        composeRule.onNodeWithText("Notified chat").assertIsDisplayed()
        composeRule.onNodeWithText("Dialog chat").assertIsDisplayed()
        composeRule.onNodeWithText("Already read").assertDoesNotExist()
        composeRule.onNodeWithText("1 hidden by filters").assertIsDisplayed()
    }

    @Test
    fun `a pinned session stays visible under the unread filter`() {
        setContent(
            state = state(
                sessions = listOf(
                    session("unread", name = "Needs attention"),
                    session("pinned", name = "Pinned chat"),
                ),
                unreadIds = setOf("unread"),
                unreadOnly = true,
                pinnedIds = setOf("pinned"),
            ),
        )

        // The filter drops the pinned chat from its project tree; the pinned
        // section above still shows it.
        composeRule.onNodeWithText("Pinned chat").assertIsDisplayed()
        composeRule.onNodeWithText("Needs attention").assertIsDisplayed()
        composeRule.onNodeWithTag(SESSION_FILTER_HIDDEN_TAG).assertDoesNotExist()
    }

    private fun state(
        sessions: List<SessionSummary> = listOf(session("s1", name = "One chat")),
        unreadIds: Set<String> = emptySet(),
        unreadOnly: Boolean = false,
        pinnedIds: Set<String> = emptySet(),
    ) = SessionsUiState(
        loading = false,
        projectsLoaded = true,
        connection = ConnectionState.Connected,
        projects = listOf(ManagedProject(PROJECT)),
        sessions = sessions,
        unreadIds = unreadIds,
        unreadOnly = unreadOnly,
        pinnedIds = pinnedIds,
        expandedProjects = setOf(PROJECT),
        activeCwd = PROJECT,
        home = "/work",
    )

    private fun session(id: String, name: String) = SessionSummary(
        id = id,
        cwd = PROJECT,
        name = name,
        modified = "2026-03-10T11:00:00Z",
        messageCount = 1,
    )

    private fun setContent(
        state: SessionsUiState,
        onRunningOnlyToggle: () -> Unit = {},
        onUnreadOnlyToggle: () -> Unit = {},
        onAgeFilterChange: (SessionAgeFilter) -> Unit = {},
        onClearFilters: () -> Unit = {},
        notificationUnreadSessionIds: Set<String> = emptySet(),
        awaitingInputSessionIds: Set<String> = emptySet(),
    ) {
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
                        onRunningOnlyToggle = onRunningOnlyToggle,
                        onUnreadOnlyToggle = onUnreadOnlyToggle,
                        onAgeFilterChange = onAgeFilterChange,
                        onClearFilters = onClearFilters,
                        notificationUnreadSessionIds = notificationUnreadSessionIds,
                        awaitingInputSessionIds = awaitingInputSessionIds,
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
    }

    private companion object {
        const val PROJECT = "/work/project"
    }
}
