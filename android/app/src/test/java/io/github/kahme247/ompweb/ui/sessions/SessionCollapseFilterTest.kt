package io.github.kahme247.ompweb.ui.sessions

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.domain.SessionAgeFilter
import io.github.kahme247.ompweb.domain.comparableProjectPath
import io.github.kahme247.ompweb.events.ConnectionState
import io.github.kahme247.ompweb.net.ManagedProject
import io.github.kahme247.ompweb.net.SessionSummary
import io.github.kahme247.ompweb.screenshot.ScreenshotMatrix
import io.github.kahme247.ompweb.ui.theme.OmpWebTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * The filter bar narrows the session list; it must not take the project
 * toggle away. A project header collapses and expands exactly as it does
 * without chips, and the five-root cap with "Show more" still applies to the
 * matching rows (the user reported that `Age: Today` left every project
 * permanently open). A search query is a result set instead: it opens every
 * project with a match, shows all matches uncapped, and leaves the stored
 * expand/collapse state alone for when the field is cleared.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h890dp-mdpi", application = android.app.Application::class)
class SessionCollapseFilterTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `the Today age filter leaves the project toggle alone`() {
        assertCollapseSurvives(state(age = SessionAgeFilter.Today))
    }

    @Test
    fun `the Running chip leaves the project toggle alone`() {
        assertCollapseSurvives(
            state(runningOnly = true, runningIds = setOf("chat")),
        )
    }

    @Test
    fun `the Unread chip leaves the project toggle alone`() {
        assertCollapseSurvives(
            state(unreadOnly = true, unreadIds = setOf("chat")),
        )
    }

    @Test
    fun `a search opens a collapsed project and shows every match`() {
        val sessions = (1..7).map { index ->
            SessionSummary(
                id = "s$index",
                cwd = PROJECT,
                name = "Session-$index",
                // Equal timestamps keep the given order (stable sort), so the
                // cap would hide exactly the last two rows.
                modified = NOW,
                messageCount = 1,
            )
        }
        val state = mutableStateOf(
            state(sessions = sessions, searchQuery = "Session", expandedProjects = emptySet()),
        )
        setContent(state)

        // The project was collapsed, yet every match is on screen: a query is
        // a result set, not a tree the user has to unfold by hand.
        composeRule.onNodeWithText("Session-1").assertIsDisplayed()
        composeRule.onNodeWithText("Session-6").assertIsDisplayed()
        composeRule.onNodeWithText("Session-7").assertIsDisplayed()
        // No cap and no "Show more" action while matching.
        composeRule.onNodeWithText(context.getString(R.string.session_show_more, 2))
            .assertDoesNotExist()
        assertEquals(
            "the query must not touch the stored expansion",
            emptySet<String>(),
            state.value.expandedProjects,
        )
    }

    @Test
    fun `clearing the query restores the previous collapsed state`() {
        val state = mutableStateOf(
            state(searchQuery = "Chat one", expandedProjects = emptySet()),
        )
        setContent(state)

        composeRule.onNodeWithText("Chat one").assertIsDisplayed()
        assertEquals(
            "showing a match must not rewrite the stored expansion",
            emptySet<String>(),
            state.value.expandedProjects,
        )

        state.value = state.value.copy(searchQuery = "")
        composeRule.waitForIdle()

        // The user collapsed this project before searching; the cleared field
        // hands the tree back exactly as they left it.
        composeRule.onNodeWithText("Chat one").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.project_expand))
            .assertIsDisplayed()
        assertEquals(emptySet<String>(), state.value.expandedProjects)
    }

    @Test
    fun `clearing the query restores the five-root cap`() {
        val sessions = (1..7).map { index ->
            SessionSummary(
                id = "s$index",
                cwd = PROJECT,
                name = "Session-$index",
                modified = NOW,
                messageCount = 1,
            )
        }
        val state = mutableStateOf(
            state(sessions = sessions, searchQuery = "Session"),
        )
        setContent(state)

        composeRule.onNodeWithText("Session-7").assertIsDisplayed()

        state.value = state.value.copy(searchQuery = "")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Session-5").assertIsDisplayed()
        composeRule.onNodeWithText("Session-6").assertDoesNotExist()
        composeRule.onNodeWithText("Session-7").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.session_show_more, 2))
            .assertIsDisplayed()
    }

    @Test
    fun `the five-root cap and Show more still apply under a filter`() {
        val sessions = (1..7).map { index ->
            SessionSummary(
                id = "s$index",
                cwd = PROJECT,
                name = "Session-$index",
                // Equal timestamps keep the given order (stable sort), so the
                // cap hides exactly the last two rows.
                modified = NOW,
                messageCount = 1,
            )
        }
        setContent(
            mutableStateOf(
                state(sessions = sessions, age = SessionAgeFilter.Today),
            ),
        )

        composeRule.onNodeWithText("Session-5").assertIsDisplayed()
        composeRule.onNodeWithText("Session-6").assertDoesNotExist()
        composeRule.onNodeWithText("Session-7").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.session_show_more, 2))
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Session-6").assertIsDisplayed()
        composeRule.onNodeWithText("Session-7").assertIsDisplayed()
    }

    /** Taps the chevron twice: the tree must disappear, then come back. */
    private fun assertCollapseSurvives(initial: SessionsUiState) {
        val state = mutableStateOf(initial)
        setContent(state)

        composeRule.onNodeWithText("Chat one").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.project_collapse))
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Chat one").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(context.getString(R.string.project_expand))
            .assertIsDisplayed()
        assertEquals("the toggle must clear the project key", emptySet<String>(), state.value.expandedProjects)

        composeRule.onNodeWithContentDescription(context.getString(R.string.project_expand))
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Chat one").assertIsDisplayed()
        assertEquals(
            "the toggle must re-add the project key",
            setOf(comparableProjectPath(PROJECT)),
            state.value.expandedProjects,
        )
    }

    private fun setContent(state: MutableState<SessionsUiState>) {
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
                        onToggleProject = { toggleProject(state, it) },
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

    /** The view model's [SessionsViewModel.toggleProject] logic, so the pane sees a real toggle. */
    private fun toggleProject(state: MutableState<SessionsUiState>, projectPath: String) {
        val key = comparableProjectPath(projectPath)
        val current = state.value.expandedProjects ?: emptySet()
        state.value = state.value.copy(
            expandedProjects = if (key in current) current - key else current + key,
        )
    }

    private fun state(
        sessions: List<SessionSummary> = listOf(chat("Chat one")),
        runningIds: Set<String> = emptySet(),
        unreadIds: Set<String> = emptySet(),
        runningOnly: Boolean = false,
        unreadOnly: Boolean = false,
        age: SessionAgeFilter = SessionAgeFilter.All,
        searchQuery: String = "",
        expandedProjects: Set<String>? = setOf(PROJECT),
    ) = SessionsUiState(
        loading = false,
        projectsLoaded = true,
        connection = ConnectionState.Connected,
        projects = listOf(ManagedProject(PROJECT)),
        sessions = sessions,
        runningIds = runningIds,
        unreadIds = unreadIds,
        runningOnly = runningOnly,
        unreadOnly = unreadOnly,
        ageFilter = age,
        searchQuery = searchQuery,
        expandedProjects = expandedProjects,
        activeCwd = PROJECT,
        home = "/work",
    )

    private fun chat(name: String) = SessionSummary(
        id = "chat",
        cwd = PROJECT,
        name = name,
        modified = NOW,
        messageCount = 1,
    )

    private companion object {
        const val PROJECT = "/work/project"

        /** Passes the `Today` preset whenever the test runs. */
        val NOW: String = Instant.now().toString()
    }
}
