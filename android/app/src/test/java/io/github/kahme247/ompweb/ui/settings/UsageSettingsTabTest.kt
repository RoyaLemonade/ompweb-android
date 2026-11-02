package io.github.kahme247.ompweb.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.data.SettingsSnapshot
import io.github.kahme247.ompweb.data.SettingsViewPreferences
import io.github.kahme247.ompweb.domain.UpdateKind
import io.github.kahme247.ompweb.net.ApiClient
import io.github.kahme247.ompweb.net.ServerAddress
import io.github.kahme247.ompweb.net.ServerAddressResult
import io.github.kahme247.ompweb.net.systemApi
import io.github.kahme247.ompweb.screenshot.ScreenshotMatrix
import io.github.kahme247.ompweb.testutil.WAIT_TIMEOUT_MS
import io.github.kahme247.ompweb.ui.theme.OmpWebTheme
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Usage tab's three load states. The tab used to render `report == null`
 * as a progress label no matter what happened, so a failed or timed-out scan
 * (a cold one takes the server a minute and more) looked exactly like a scan
 * that never finishes — the user's "it keeps loading".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h1500dp-mdpi", application = android.app.Application::class)
class UsageSettingsTabTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var viewModel: SettingsViewModel
    private var answer: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    private val prefs = object : SettingsViewPreferences {
        private val state = MutableStateFlow(SettingsSnapshot(loaded = true))
        override val snapshot: StateFlow<SettingsSnapshot> = state.asStateFlow()

        override suspend fun setDismissedUpdate(kind: UpdateKind, version: String?) {
            state.update { it.copy(dismissedAppUpdateVersion = version) }
        }

        override suspend fun setCompletedAppUpdate(version: String?) {
            state.update { it.copy(completedAppUpdateVersion = version) }
        }

        override suspend fun setDictationEngine(wire: String) = Unit

        override suspend fun setDictationLanguage(tag: String?) = Unit
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request)
        }
        server.start()
        val address = (ServerAddress.parse("http://127.0.0.1:${server.port}") as ServerAddressResult.Valid).address
        val api = ApiClient(OkHttpClient(), { address })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val poller = ProviderUsagePoller(load = { api.systemApi().providerUsage(it) }, scope = scope)
        viewModel = SettingsViewModel(api.systemApi(), prefs, poller)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun showTab() {
        composeRule.setContent {
            OmpWebTheme(themeId = ScreenshotMatrix.LIGHT_THEME_ID) {
                UsageSettingsTab(viewModel = viewModel)
            }
        }
    }

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * Robolectric runs the test on the main thread, and `viewModelScope`
     * resumes there; a blocking wait would hold that looper and the answer
     * would never be applied. Drain the looper between polls instead.
     */
    private fun awaitUsage(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $message; usage=${viewModel.usageState.value}")
    }

    @Test
    fun aFailedScanShowsTheErrorAndRetryInsteadOfALoadingLabel() {
        answer = { json("""{"error":"scan failed"}""", 500) }
        viewModel.loadUsage()
        awaitUsage("the failure") { !viewModel.usageState.value.loading }

        showTab()

        composeRule.onNodeWithText(text(R.string.usage_error_title)).assertIsDisplayed()
        composeRule.onNodeWithText("scan failed").assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.settings_retry)).assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.usage_scanning)).assertCountEquals(0)
        composeRule.onAllNodesWithText(text(R.string.usage_loading)).assertCountEquals(0)
    }

    @Test
    fun retryLoadsTheReportAfterTheFailure() {
        answer = { json("""{"error":"scan failed"}""", 500) }
        viewModel.loadUsage()
        awaitUsage("the failure") { !viewModel.usageState.value.loading }
        answer = { json(report()) }

        showTab()
        composeRule.onNodeWithText(text(R.string.settings_retry)).performClick()

        awaitUsage("the retried report") { viewModel.usageState.value.report != null }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(R.string.usage_providers)).assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.usage_error_title)).assertCountEquals(0)
    }

    @Test
    fun aRunningScanIsNamedAndNeverLooksLikeAFailure() {
        answer = { json(report()).setHeadersDelay(30, TimeUnit.SECONDS) }
        viewModel.loadUsage()

        showTab()

        // The page body owns the first load; the controls card repeats it only
        // for a refresh over an existing report.
        composeRule.onAllNodesWithText(text(R.string.usage_scanning)).assertCountEquals(1)
        composeRule.onAllNodesWithText(text(R.string.usage_error_title)).assertCountEquals(0)
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun report(): String = """
        {"timeRange":"30d","granularity":"daily",
         "summary":{"totalCost":1.5,"totalTokens":1000,"inputTokens":800,"outputTokens":200,
                    "reasoningTokens":0,"cacheReadTokens":0,"cacheWriteTokens":0,"cacheSavings":0,
                    "activeDays":1,"tokensPerActiveDay":1000,"cachePercentage":0,
                    "costQuality":{"providerReported":100,"modelPriced":0,"unpriced":0}},
         "providers":[{"provider":"claude","name":"Claude","cost":1.5,"tokens":1000,"share":100,"color":"#d97757"}],
         "timeSeries":[{"date":"2026-11-01","label":"Nov 1","timestamp":1761955200000,"totalCost":1.5,
                        "totalTokens":1000,"byProvider":{"claude":{"cost":1.5,"tokens":1000}}}],
         "modelBreakdown":[{"model":"claude-opus-4","provider":"claude","cost":1.5,"tokens":1000,
                            "inputTokens":800,"outputTokens":200,"cacheReadTokens":0,"cacheWriteTokens":0,
                            "reasoningTokens":0,"recordsCount":1,"share":100}],
         "dayBreakdown":[{"date":"2026-11-01","label":"Nov 1, 2026","cost":1.5,"tokens":1000,
                          "inputTokens":800,"outputTokens":200,"cacheReadTokens":0,"share":100}],
         "projectBreakdown":[{"project":"C:\\work\\ompweb","projectName":"ompweb","cost":1.5,"tokens":1000,
                              "share":100,"sessionsCount":1}],
         "scanInfo":{"transcriptsScanned":1,"transcriptsOutsideWindow":0,"usageRecordsCount":1,
                     "durationSeconds":0.1,"scannedAt":1762041600000}}
    """.trimIndent()
}
