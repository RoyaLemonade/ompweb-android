package io.github.kahme247.ompweb.data

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The device's memo of "newest message per session": monotonic, persistent
 * across a restart, merged with a record that raced the initial read, and
 * tolerant of a file it cannot parse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionActivityStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val file: File get() = File(temp.root, SessionActivityStore.FILE_NAME)

    private fun TestScope.store(maxEntries: Int = SessionActivityStore.MAX_ENTRIES): SessionActivityStore =
        SessionActivityStore(file, this, UnconfinedTestDispatcher(testScheduler), maxEntries)

    @Test
    fun recordsAreReadableAndSurviveARestart() = runTest {
        val store = store()
        store.record("s1", 1_000)
        store.record("s2", 2_000)
        advanceUntilIdle()
        assertEquals(mapOf("s1" to 1_000L, "s2" to 2_000L), store.known.value)

        val restarted = store()
        advanceUntilIdle()
        assertEquals(mapOf("s1" to 1_000L, "s2" to 2_000L), restarted.known.value)
        assertEquals(2_000L, restarted.known.value["s2"])
    }

    @Test
    fun anOlderRecordNeverReplacesANewerOne() = runTest {
        val store = store()
        store.record("s1", 5_000)
        store.record("s1", 3_000)
        advanceUntilIdle()

        assertEquals(5_000L, store.known.value["s1"])
    }

    @Test
    fun `a record that races the initial read keeps both sides`() = runTest {
        // The file already holds an entry; the read runs on the test dispatcher,
        // so the synchronous record lands first.
        val seeded = store()
        seeded.record("old", 1_000)
        advanceUntilIdle()

        val store = store()
        store.record("new", 9_000)
        advanceUntilIdle()

        assertEquals(mapOf("old" to 1_000L, "new" to 9_000L), store.known.value)
    }

    @Test
    fun blankIdsAndNonPositiveTimesAreIgnored() = runTest {
        val store = store()
        store.record("", 1_000)
        store.record("s1", 0)
        store.record("s1", -5)
        advanceUntilIdle()

        assertTrue(store.known.value.isEmpty())
    }

    @Test
    fun clearForgetsEverythingAndDeletesTheFile() = runTest {
        val store = store()
        store.record("s1", 1_000)
        advanceUntilIdle()
        assertTrue(file.isFile)

        store.clear()
        advanceUntilIdle()

        assertTrue(store.known.value.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun aClearDuringTheInitialReadDoesNotResurrectTheForgottenIds() = runTest(UnconfinedTestDispatcher()) {
        // The file already holds an entry, and the initial read is in flight
        // while `clear()` runs (a logout/401 that races the first load). The
        // read's answer is older than the clear and must be dropped — it used
        // to be merged back, so the next `record` wrote the forgotten ids to
        // disk again.
        file.writeText("""{"sessions":{"old":1000}}""")
        // Both dispatchers share one scheduler, so `advanceUntilIdle` alone
        // decides the interleaving: the read is queued (and holds the load
        // mutex) when `clear()` runs, never a sleeping test thread.
        val io = StandardTestDispatcher(testScheduler)
        val store = SessionActivityStore(file, this, io)

        store.clear()
        advanceUntilIdle()

        assertTrue("the forgotten entry stays forgotten", store.known.value.isEmpty())
        assertFalse("the clear owns the file", file.exists())

        store.record("fresh", 5_000)
        advanceUntilIdle()
        assertEquals(setOf("fresh"), store.known.value.keys)
        assertTrue("the new entry is written", file.isFile)
        val written = file.readText()
        assertTrue("the record is persisted: $written", written.contains("fresh"))
        assertFalse("a later record does not write the cleared ids back", written.contains("old"))
    }

    @Test
    fun aBrokenFileReadsAsEmptyAndIsDropped() = runTest {
        file.writeText("{not json")
        val store = store()
        advanceUntilIdle()

        assertTrue(store.known.value.isEmpty())
        assertFalse("the unreadable document is dropped", file.exists())
    }

    @Test
    fun theOldestEntriesAreTrimmed() = runTest {
        val store = store(maxEntries = 2)
        store.record("oldest", 1_000)
        store.record("middle", 2_000)
        store.record("newest", 3_000)
        advanceUntilIdle()

        assertEquals(setOf("middle", "newest"), store.known.value.keys)
        assertNull(store.known.value["oldest"])
    }
}
