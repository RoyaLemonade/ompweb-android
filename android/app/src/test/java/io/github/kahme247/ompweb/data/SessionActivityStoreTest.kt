package io.github.kahme247.ompweb.data

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
