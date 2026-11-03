package io.github.kahme247.ompweb.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.data.AndroidUpdatePreferences
import io.github.kahme247.ompweb.data.AndroidUpdatePreferencesSnapshot
import io.github.kahme247.ompweb.domain.AndroidApkIdentity
import io.github.kahme247.ompweb.domain.AndroidUpdateCache
import io.github.kahme247.ompweb.domain.AndroidUpdateCandidate
import io.github.kahme247.ompweb.domain.sha256Hex
import io.github.kahme247.ompweb.net.AndroidUpdateDownload
import io.github.kahme247.ompweb.net.AndroidUpdateDownloader
import io.github.kahme247.ompweb.net.AndroidUpdateSource
import io.github.kahme247.ompweb.screenshot.ScreenshotMatrix
import io.github.kahme247.ompweb.ui.theme.OmpWebTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The settings card's install action must not depend on the shell banner.
 *
 * Reported for 1.0.34: the user declined the update (the banner's Dismiss, or
 * the mobile-data question's Cancel) and afterwards Settings said "Version X
 * is available" without offering a single button — the card gated its install
 * action on the *banner* state, which every decline clears. Each decline path
 * therefore has to leave the card's action in place.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h2400dp-mdpi", application = android.app.Application::class)
class AndroidAppUpdateCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        metered: Boolean = false,
        installer: FakeInstaller = FakeInstaller(),
    ) = AndroidUpdateController(
        source = FakeSource(),
        settings = FakePrefs(),
        scope = scope,
        currentVersion = "1.0.34",
        installedPackageName = "io.github.kahme247.ompweb",
        currentVersionCode = 10034,
        downloader = FakeDownloader(),
        inspector = FakeInspector,
        installer = installer,
        installStatus = installer.status,
        expectedSignerSha256 = SIGNER,
        metered = { metered },
    )

    private fun render(controller: AndroidUpdateController) {
        composeRule.setContent {
            OmpWebTheme(themeId = ScreenshotMatrix.LIGHT_THEME_ID) {
                AndroidAppUpdateCard(controller = controller, appVersion = "1.0.34")
            }
        }
        composeRule.waitForIdle()
    }

    private fun text(id: Int): String = context.getString(id)

    @Test
    fun `dismissing the banner keeps the install action in the settings card`() {
        val controller = controller()
        controller.check()
        composeRule.waitForIdle()
        controller.dismiss("1.0.35")

        render(controller)

        composeRule.onNodeWithText(text(R.string.system_android_update_available).format("1.0.35"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.system_android_update_install)).assertIsDisplayed()
    }

    @Test
    fun `cancelling the mobile-data question returns to the install action`() {
        val controller = controller(metered = true)
        controller.check()
        composeRule.waitForIdle()
        controller.dismiss("1.0.35")

        render(controller)

        // The install starts, asks for the mobile-data yes, and the user says no.
        composeRule.onNodeWithText(text(R.string.system_android_update_install)).performClick()
        composeRule.onNodeWithText(text(R.string.android_update_banner_metered_confirm))
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.system_android_update_cancel)).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(text(R.string.system_android_update_install)).assertIsDisplayed()
    }

    @Test
    fun `a refused install keeps the retry action`() {
        val installer = FakeInstaller()
        val controller = controller(installer = installer)
        controller.check()
        composeRule.waitForIdle()
        controller.dismiss("1.0.35")

        render(controller)

        composeRule.onNodeWithText(text(R.string.system_android_update_install)).performClick()
        composeRule.waitForIdle()
        // Android's confirmation is declined; the session reports the abort.
        installer.status.tryEmit(
            AndroidInstallSessionStatus.Failed(
                installer.sessionId,
                "INSTALL_FAILED_ABORTED: User rejected permissions",
            ),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText(text(R.string.system_android_update_retry)).assertIsDisplayed()
    }

    private class FakeSource : AndroidUpdateSource {
        override suspend fun latest(
            feedUrl: String?,
            installedVersionName: String,
            installedVersionCode: Long,
        ): AndroidUpdateCandidate = AndroidUpdateCandidate(
            versionName = "1.0.35",
            versionCode = 10035,
            apkUrl = "https://updates.example.com/ompweb-1.0.35.apk",
            apkSize = 64L,
            sha256 = "a".repeat(64),
        )
    }

    private class FakePrefs : AndroidUpdatePreferences {
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

    /** Answers with a file whose digest is what the source announced. */
    private class FakeDownloader : AndroidUpdateDownloader {
        override suspend fun download(
            candidate: AndroidUpdateCandidate,
            onProgress: (received: Long, total: Long) -> Unit,
        ): AndroidUpdateDownload {
            val file = File.createTempFile("ompweb-update", ".apk").apply {
                writeText("fake apk")
                deleteOnExit()
            }
            onProgress(file.length(), file.length())
            val digest = sha256Hex(file).orEmpty()
            // The source announced "a"*64; the check below reads the file, so
            // the download reports the digest the check will compare against.
            return AndroidUpdateDownload(file, digest, file.length())
        }
    }

    private object FakeInspector : AndroidApkInspector {
        override fun installed(): AndroidApkIdentity = AndroidApkIdentity(
            packageName = "io.github.kahme247.ompweb",
            versionCode = 10034,
            versionName = "1.0.34",
            signerSha256 = setOf(SIGNER),
        )

        override fun inspect(file: File): AndroidApkIdentity = AndroidApkIdentity(
            packageName = "io.github.kahme247.ompweb",
            versionCode = 10035,
            versionName = "1.0.35",
            signerSha256 = setOf(SIGNER),
        )
    }

    private class FakeInstaller : AndroidApkInstaller {
        val status = MutableSharedFlow<AndroidInstallSessionStatus>(extraBufferCapacity = 8)
        override val canInstallPackages: Boolean = true
        override val sessionStatus: Flow<AndroidInstallSessionStatus> = status
        val sessionId = 7

        override fun openInstallPermissionSettings() = Unit

        override fun openPendingConfirmation(): Boolean = true

        override suspend fun install(apk: File, onSession: (Int) -> Unit) {
            onSession(sessionId)
        }
    }

    private companion object {
        /** The pinned release certificate; both fakes and the controller use it. */
        const val SIGNER = "abababababababababababababababababababababababababababababababab"
    }
}
