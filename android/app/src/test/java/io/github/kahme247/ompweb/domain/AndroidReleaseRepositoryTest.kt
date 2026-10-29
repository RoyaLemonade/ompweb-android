package io.github.kahme247.ompweb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single source of the repository the in-app updater reads: the build
 * config field `UPDATE_RELEASES_REPO` (`android/app/build.gradle.kts`). The
 * release source and both URL allow-lists derive from it, so a repository move
 * cannot leave one of them pointing at the old releases.
 */
class AndroidReleaseRepositoryTest {

    @Test
    fun `the updater reads the app's own repository`() {
        assertEquals("RoyaLemonade/ompweb-android", AndroidReleaseRepository.slug)
        assertEquals("RoyaLemonade", AndroidReleaseRepository.owner)
        assertEquals("ompweb-android", AndroidReleaseRepository.repo)
    }

    @Test
    fun `the URL allow-lists are derived from the same repository`() {
        assertEquals("/RoyaLemonade/ompweb-android/releases/", AndroidReleaseRepository.pagePath)
        assertEquals(
            "/RoyaLemonade/ompweb-android/releases/download/",
            AndroidReleaseRepository.assetPath,
        )

        assertTrue(
            isAndroidReleasePage(
                "https://github.com/RoyaLemonade/ompweb-android/releases/tag/android-v1.0.17",
            ),
        )
        assertTrue(
            isAndroidReleaseAsset(
                "https://github.com/RoyaLemonade/ompweb-android/releases/download/android-v1.0.17/ompweb-1.0.17.apk",
            ),
        )
        // The upstream ompweb repository publishes the web app, not this APK.
        assertFalse(
            isAndroidReleasePage("https://github.com/kahme247/ompweb/releases/tag/android-v1.0.17"),
        )
        assertFalse(
            isAndroidReleaseAsset(
                "https://github.com/kahme247/ompweb/releases/download/android-v1.0.17/ompweb-1.0.17.apk",
            ),
        )
    }
}
