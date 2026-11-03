package io.github.kahme247.ompweb.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.data.AndroidUpdatePreferences
import io.github.kahme247.ompweb.data.AndroidUpdatePreferencesSnapshot
import io.github.kahme247.ompweb.data.SettingsSnapshot
import io.github.kahme247.ompweb.data.SettingsViewPreferences
import io.github.kahme247.ompweb.domain.AndroidUpdateCache
import io.github.kahme247.ompweb.domain.AndroidUpdateCandidate
import io.github.kahme247.ompweb.domain.UpdateKind
import io.github.kahme247.ompweb.net.AppUpdateInfo
import io.github.kahme247.ompweb.net.AndroidUpdateSource
import io.github.kahme247.ompweb.net.MobileAccessInfo
import io.github.kahme247.ompweb.net.OmpUpdateInfo
import io.github.kahme247.ompweb.net.ProviderUsageSnapshot
import io.github.kahme247.ompweb.net.ReleaseNotes
import io.github.kahme247.ompweb.net.RestartSessionsResult
import io.github.kahme247.ompweb.net.SelfUpdatePrepare
import io.github.kahme247.ompweb.net.SelfUpdateStatus
import io.github.kahme247.ompweb.net.ServerAppInfo
import io.github.kahme247.ompweb.net.SystemApi
import io.github.kahme247.ompweb.net.UsageGranularity
import io.github.kahme247.ompweb.net.UsageReport
import io.github.kahme247.ompweb.net.UsageTimeRange
import io.github.kahme247.ompweb.net.WebServerSettings
import io.github.kahme247.ompweb.net.WindowsServiceAction
import io.github.kahme247.ompweb.net.WindowsServiceActionInfo
import io.github.kahme247.ompweb.net.WindowsServiceInfo
import io.github.kahme247.ompweb.screenshot.ScreenshotMatrix
import io.github.kahme247.ompweb.ui.theme.OmpWebTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The System & Updates tab's order after the 1.0.35 review: the app's own
 * update card and the server's update notices lead the tab, above the server
 * settings, the Mobile app card and About.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h2600dp-mdpi", application = android.app.Application::class)
class SystemSettingsTabTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the app update and the server updates lead the tab`() {
        val viewModel = SettingsViewModel(
            system = UnusedSystemApi,
            settings = FakeViewPreferences(),
            poller = ProviderUsagePoller(
                load = { ProviderUsageSnapshot(generatedAt = null, reports = emptyList()) },
                scope = scope,
            ),
        )
        val controller = AndroidUpdateController(
            source = FakeSource(),
            settings = FakeUpdatePrefs(),
            scope = scope,
            currentVersion = "1.0.34",
            installedPackageName = "io.github.kahme247.ompweb",
            currentVersionCode = 10034,
            installStatus = emptyFlow(),
        )
        controller.check()

        composeRule.setContent {
            OmpWebTheme(themeId = ScreenshotMatrix.LIGHT_THEME_ID) {
                SystemSettingsTab(
                    viewModel = viewModel,
                    onSignOut = {},
                    onChangeServer = {},
                    modifier = Modifier.fillMaxSize(),
                    androidUpdates = controller,
                    appVersion = "1.0.34",
                )
            }
        }
        composeRule.waitForIdle()

        fun top(id: Int): Float =
            composeRule.onNodeWithText(context.getString(id)).fetchSemanticsNode().boundsInRoot.top

        val appUpdate = top(R.string.system_android_update_title)
        val serverUpdates = top(R.string.system_app_update)
        val serverSettings = top(R.string.system_server_title)
        val about = top(R.string.system_about_title)

        assertTrue(
            "the Android app update card leads the tab (app=$appUpdate, server updates=$serverUpdates)",
            appUpdate < serverUpdates,
        )
        assertTrue(
            "the server update notices come before the server settings " +
                "(updates=$serverUpdates, settings=$serverSettings)",
            serverUpdates < serverSettings,
        )
        assertTrue("About stays at the end (settings=$serverSettings, about=$about)", serverSettings < about)
    }

    /** Every endpoint unused by this test; `loadSystem` catches the throw. */
    private object UnusedSystemApi : SystemApi {
        override suspend fun appInfo(): ServerAppInfo = error("unused")
        override suspend fun appUpdate(force: Boolean): AppUpdateInfo = error("unused")
        override suspend fun appUpdateNotes(): ReleaseNotes? = error("unused")
        override suspend fun appUpdatePrepare(): SelfUpdatePrepare = error("unused")
        override suspend fun appUpdateCommit(attemptId: String) = error("unused")
        override suspend fun appUpdateStatus(): SelfUpdateStatus? = error("unused")
        override suspend fun acknowledgeAppUpdate(attemptId: String) = error("unused")
        override suspend fun ompUpdateCheck(force: Boolean): OmpUpdateInfo = error("unused")
        override suspend fun ompUpdatePrepare(): SelfUpdatePrepare = error("unused")
        override suspend fun ompUpdateCommit(attemptId: String) = error("unused")
        override suspend fun ompUpdateStatus(): SelfUpdateStatus? = error("unused")
        override suspend fun acknowledgeOmpUpdate(attemptId: String) = error("unused")
        override suspend fun restartSessions(): RestartSessionsResult = error("unused")
        override suspend fun webSettings(): WebServerSettings = error("unused")
        override suspend fun saveWebSettings(autoResumeSessions: Boolean, agentEnv: String?): WebServerSettings =
            error("unused")
        override suspend fun mobileAccess(): MobileAccessInfo = error("unused")
        override suspend fun windowsService(): WindowsServiceInfo = error("unused")
        override suspend fun windowsAction(
            action: WindowsServiceAction,
            cleanConfig: Boolean,
        ): WindowsServiceActionInfo = error("unused")
        override suspend fun usage(
            range: UsageTimeRange,
            granularity: UsageGranularity,
            refresh: Boolean,
        ): UsageReport = error("unused")
        override suspend fun providerUsage(refresh: Boolean): ProviderUsageSnapshot = error("unused")
    }

    private class FakeViewPreferences : SettingsViewPreferences {
        private val state = MutableStateFlow(SettingsSnapshot(loaded = true))
        override val snapshot: StateFlow<SettingsSnapshot> = state

        override suspend fun setDismissedUpdate(kind: UpdateKind, version: String?) = Unit

        override suspend fun setCompletedAppUpdate(version: String?) = Unit

        override suspend fun setDictationEngine(wire: String) = Unit

        override suspend fun setDictationLanguage(tag: String?) = Unit
    }

    private class FakeSource : AndroidUpdateSource {
        override suspend fun latest(
            feedUrl: String?,
            installedVersionName: String,
            installedVersionCode: Long,
        ): AndroidUpdateCandidate = AndroidUpdateCandidate(versionName = "1.0.35", versionCode = 10035)
    }

    private class FakeUpdatePrefs : AndroidUpdatePreferences {
        private val flow = MutableStateFlow(AndroidUpdatePreferencesSnapshot())
        override val androidUpdatePreferences: StateFlow<AndroidUpdatePreferencesSnapshot> = flow

        override suspend fun setDismissedAndroidUpdate(version: String?) {
            flow.update { it.copy(dismissedVersion = version) }
        }

        override suspend fun setAndroidUpdateCache(cache: AndroidUpdateCache) {
            flow.update { it.copy(cache = cache) }
        }

        override suspend fun setAndroidUpdateFeedUrl(url: String?) {
            flow.update { it.copy(feedUrl = url) }
        }
    }
}
