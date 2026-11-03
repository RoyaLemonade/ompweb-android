package io.github.kahme247.ompweb.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.domain.AndroidUpdateNotice
import io.github.kahme247.ompweb.domain.SettingsTab
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
 * The category list's compact update row (1.0.35): while an app update waits,
 * "Update available / vX → vY / Install" sits above the first category in both
 * layouts (the phone list and the rail's navigation), and tapping it opens the
 * update card. Without an update the list starts with the search field, as
 * before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h890dp-mdpi", application = android.app.Application::class)
class SettingsUpdateShortcutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun text(id: Int): String = context.getString(id)

    private fun render(update: AndroidUpdateNotice?, onOpenUpdate: () -> Unit = {}) {
        composeRule.setContent {
            OmpWebTheme(themeId = ScreenshotMatrix.LIGHT_THEME_ID) {
                SettingsNavigation(
                    modifier = Modifier.fillMaxSize(),
                    selected = null,
                    cwd = "/home/dev/projects/ompweb",
                    query = "",
                    onQueryChange = {},
                    onSelect = {},
                    update = update,
                    onOpenUpdate = onOpenUpdate,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `an announced update leads the category list and opens the card`() {
        var opened = 0
        render(
            update = AndroidUpdateNotice(currentVersion = "1.0.34", availableVersion = "1.0.35"),
            onOpenUpdate = { opened++ },
        )

        composeRule.onNodeWithTag(SETTINGS_UPDATE_SHORTCUT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_update_shortcut_title)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.android_update_banner_version).format("1.0.34", "1.0.35"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_update_shortcut_action)).assertIsDisplayed()

        val shortcutTop = composeRule.onNodeWithTag(SETTINGS_UPDATE_SHORTCUT_TAG)
            .fetchSemanticsNode().boundsInRoot.top
        val firstCategoryTop = composeRule.onNodeWithText(text(R.string.settings_tab_general))
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the shortcut is above the first category (shortcut=$shortcutTop, category=$firstCategoryTop)",
            shortcutTop < firstCategoryTop,
        )

        composeRule.onNodeWithText(text(R.string.settings_update_shortcut_action)).performClick()
        assertTrue("the tap opens the update card", opened == 1)
    }

    @Test
    fun `without an update the category list is unchanged`() {
        render(update = null)

        composeRule.onNodeWithTag(SETTINGS_UPDATE_SHORTCUT_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.settings_tab_general)).assertIsDisplayed()
        // The search field stays the first row.
        composeRule.onNodeWithText(text(R.string.settings_search_hint)).assertIsDisplayed()
    }
}
