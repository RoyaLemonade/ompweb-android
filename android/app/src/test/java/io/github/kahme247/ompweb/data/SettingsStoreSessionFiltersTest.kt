package io.github.kahme247.ompweb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.domain.SessionAgeFilter
import io.github.kahme247.ompweb.domain.SessionListFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sessions list's filter chips are per server origin and must survive a
 * storage round-trip (DataStore encode/decode). The default filter costs no
 * storage, and `clearSessionFilters` — the sign-out/switch path — forgets only
 * the origin being left.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreSessionFiltersTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `filter chips survive a storage round-trip per origin and clear on sign-out`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsStore(context, scope)
        val origin = "http://filters.invalid:30178"
        val other = "http://other.invalid:30178"
        val unread = SessionListFilter(runningOnly = true, unreadOnly = true, age = SessionAgeFilter.TwoDays)

        val initial = withTimeout(20_000) { store.snapshot.first { it.loaded } }
        assertEquals("a fresh install has no filters", emptyMap<String, SessionListFilter>(), initial.sessionFiltersByOrigin)

        store.setSessionFilters(origin, unread)
        store.setSessionFilters(other, SessionListFilter(age = SessionAgeFilter.SevenDays))
        // Another edit forces a fresh DataStore emission, so the assertions
        // below run against decoded storage, not the in-memory write.
        store.setTheme("filters-test")

        val reloaded = withTimeout(20_000) {
            store.snapshot.first { it.loaded && it.themeId == "filters-test" }
        }
        assertEquals(unread, reloaded.sessionFiltersByOrigin[origin])
        assertEquals(SessionListFilter(age = SessionAgeFilter.SevenDays), reloaded.sessionFiltersByOrigin[other])

        // The default filter removes only that origin's record.
        store.setSessionFilters(other, SessionListFilter())
        val emptied = withTimeout(5_000) {
            store.snapshot.first { it.loaded && !it.sessionFiltersByOrigin.containsKey(other) }
        }
        assertEquals(unread, emptied.sessionFiltersByOrigin[origin])

        store.setSessionFilters(other, SessionListFilter(unreadOnly = true))
        withTimeout(5_000) {
            store.snapshot.first { it.loaded && it.sessionFiltersByOrigin.containsKey(other) }
        }

        store.clearSessionFilters(origin)
        val cleared = withTimeout(5_000) {
            store.snapshot.first { it.loaded && !it.sessionFiltersByOrigin.containsKey(origin) }
        }
        assertEquals(
            "another installation's filters survive",
            mapOf(other to SessionListFilter(unreadOnly = true)),
            cleared.sessionFiltersByOrigin,
        )
        assertFalse("the cleared origin is empty, not defaulted to a filter", cleared.sessionFiltersByOrigin.containsKey(origin))
    }
}
