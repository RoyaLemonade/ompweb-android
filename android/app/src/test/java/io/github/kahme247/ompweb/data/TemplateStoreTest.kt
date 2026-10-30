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
 * Snippets: the `/name` derivation, expansion of a typed command, and the
 * per-device store's add/edit/reorder/delete persistence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TemplateStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun template(title: String, text: String, id: String = title) =
        ChatTemplate(id = id, title = title, text = text)

    @Test
    fun commandNameIsASlugOfTheTitle() {
        assertEquals("review-checklist", templateCommandName("Review checklist"))
        assertEquals("fix-typo", templateCommandName("  Fix   Typo  "))
        assertEquals("ci-check", templateCommandName("CI_check"))
        assertEquals("template", templateCommandName("???"))
        assertEquals("ünïcode-title", templateCommandName("Ünïcode title"))
    }

    @Test
    fun duplicateTitlesGetDistinctCommands() {
        val commands = templateCommands(
            listOf(template("Review", "a", "1"), template("Review", "b", "2")),
        )
        assertEquals(listOf("review", "review-2"), commands.map { it.name })
    }

    @Test
    fun aTypedNameExpandsToItsTextWithOptionalArguments() {
        val templates = listOf(template("Review checklist", "Check the diff."))
        assertEquals("Check the diff.", expandTemplateCommand("/review-checklist", templates))
        assertEquals("Check the diff.", expandTemplateCommand("/Review-Checklist", templates))
        assertEquals(
            "Check the diff.\n\nfor the parser",
            expandTemplateCommand("/review-checklist for the parser", templates),
        )
        assertNull("a real command is not a template", expandTemplateCommand("/goal ship it", templates))
        assertNull("plain text never expands", expandTemplateCommand("review-checklist", templates))
        assertNull(expandTemplateCommand("/unknown", templates))
    }

    @Test
    fun theRepositoryRoundTripsAndToleratesABrokenFile() {
        val file = File(temp.root, "templates.json")
        val repository = TemplateRepository(file)
        repository.save(listOf(template("One", "first"), template("Two", "second")))
        assertEquals(listOf("One", "Two"), repository.load().map { it.title })

        file.writeText("{broken")
        assertTrue("a broken file is empty, not a crash", repository.load().isEmpty())
        assertFalse("and it is dropped", file.exists())
    }

    @Test
    fun theStoreAddsEditsReordersAndDeletes() = runTest {
        val store = store()
        store.add("  One  ", " first ")
        advanceUntilIdle()
        store.add("Two", "second")
        advanceUntilIdle()
        store.add("Three", "third")
        advanceUntilIdle()
        assertEquals(listOf("One", "Two", "Three"), store.state.value.map { it.title })
        assertEquals("values are trimmed", "first", store.state.value.first().text)

        val two = store.state.value[1]
        store.update(two.id, "Two!", "changed")
        advanceUntilIdle()
        assertEquals("Two!", store.state.value[1].title)

        store.move(two.id, -1)
        advanceUntilIdle()
        assertEquals(listOf("Two!", "One", "Three"), store.state.value.map { it.title })

        store.delete(store.state.value.first().id)
        advanceUntilIdle()
        assertEquals(listOf("One", "Three"), store.state.value.map { it.title })

        // Blank title/text never create a template.
        store.add("  ", "text")
        store.add("Title", "   ")
        advanceUntilIdle()
        assertEquals(2, store.state.value.size)
    }

    @Test
    fun `a snippet the file cannot hold is not shown as saved`() = runTest {
        // A non-empty directory in the file's place makes the atomic replace
        // fail. The store must not publish a list the file does not hold, or
        // the snippet would be gone on the next start.
        val blocked = File(temp.root, "templates/templates.json")
        blocked.mkdirs()
        File(blocked, "child").writeText("x")

        val store = store()
        store.add("Keep", "this text")
        advanceUntilIdle()

        assertTrue("nothing was written, so nothing is shown", store.state.value.isEmpty())
    }

    @Test
    fun `aRestartedStoreReadsTheSavedSnippets`() = runTest {
        val root = File(temp.root, "templates")
        val first = TemplateStore(root, this, UnconfinedTestDispatcher(testScheduler))
        first.add("Keep", "this text")
        advanceUntilIdle()

        val restarted = TemplateStore(root, this, UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(listOf("Keep"), restarted.state.value.map { it.title })
    }

    /** The store runs on the test scope itself, so `advanceUntilIdle` drives it. */
    private fun TestScope.store(): TemplateStore =
        TemplateStore(File(temp.root, "templates"), this, UnconfinedTestDispatcher(testScheduler))
}
