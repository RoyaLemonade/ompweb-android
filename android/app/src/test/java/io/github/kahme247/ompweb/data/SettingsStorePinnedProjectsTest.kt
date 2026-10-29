package io.github.kahme247.ompweb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Project pins are per server origin and must survive a storage round-trip
 * (DataStore encode/decode). `clearLocalUserData` — sign-out, a server switch,
 * a 401 — forgets only the origin being left, without touching the session
 * pins or another installation's project pins.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStorePinnedProjectsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `project pins survive a storage round-trip per origin and clear on sign-out`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context, scope)
        val origin = "http://pins.invalid:30178"
        val other = "http://other.invalid:30178"

        val initial = withTimeout(20_000) { store.snapshot.first { it.loaded } }
        assertEquals("a fresh install has no project pins", emptyMap<String, List<String>>(), initial.pinnedProjectsByOrigin)

        store.setPinnedProjects(origin, listOf("/work/new", "/work/old"))
        store.setPinnedProjects(other, listOf("/work/foreign"))
        store.setPinnedSessions(origin, setOf("session-1"))
        // Another edit forces a fresh DataStore emission, so the assertions
        // below run against decoded storage, not the in-memory write.
        store.setTheme("pinned-projects-test")

        val reloaded = withTimeout(20_000) {
            store.snapshot.first { it.loaded && it.themeId == "pinned-projects-test" }
        }
        assertEquals(listOf("/work/new", "/work/old"), reloaded.pinnedProjectsByOrigin[origin])
        assertEquals(
            "pin order is the display order and is kept",
            listOf("/work/new", "/work/old"),
            reloaded.pinnedProjectsByOrigin.getValue(origin),
        )
        assertEquals(listOf("/work/foreign"), reloaded.pinnedProjectsByOrigin[other])
        assertEquals(setOf("session-1"), reloaded.pinnedSessionsByOrigin[origin])

        // An empty list removes just that origin's entry.
        store.setPinnedProjects(other, emptyList())
        val emptied = withTimeout(5_000) {
            store.snapshot.first { it.loaded && !it.pinnedProjectsByOrigin.containsKey(other) }
        }
        assertEquals(listOf("/work/new", "/work/old"), emptied.pinnedProjectsByOrigin[origin])

        store.setPinnedProjects(other, listOf("/work/foreign"))
        withTimeout(5_000) {
            store.snapshot.first { it.loaded && it.pinnedProjectsByOrigin.containsKey(other) }
        }

        // Sign-out, a server switch and a 401 forget only the origin being
        // left: paths of another installation are that installation's.
        store.clearPinnedProjects(origin)
        val cleared = withTimeout(5_000) {
            store.snapshot.first { it.loaded && !it.pinnedProjectsByOrigin.containsKey(origin) }
        }
        assertEquals(
            "another installation's pins survive",
            mapOf(other to listOf("/work/foreign")),
            cleared.pinnedProjectsByOrigin,
        )
        assertEquals(
            "clearing project pins leaves the session pins alone",
            setOf("session-1"),
            cleared.pinnedSessionsByOrigin[origin],
        )
    }
}
