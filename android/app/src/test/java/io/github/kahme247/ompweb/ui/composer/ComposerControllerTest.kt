package io.github.kahme247.ompweb.ui.composer

import io.github.kahme247.ompweb.data.ChatDraft
import io.github.kahme247.ompweb.data.ChatTemplate
import io.github.kahme247.ompweb.domain.AttachedTextFile
import io.github.kahme247.ompweb.net.ApiClient
import io.github.kahme247.ompweb.net.filesApi
import io.github.kahme247.ompweb.testutil.MemoryDrafts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Composer state shared by the chat and the new-session pane. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerControllerTest {

    private fun TestScope.controller(drafts: MemoryDrafts): ComposerController {
        // No request is issued by these tests; the client only satisfies the seams.
        val api = ApiClient(OkHttpClient(), { null })
        return ComposerController(
            drafts = drafts,
            scope = backgroundScope,
            api = api,
            files = api.filesApi(),
            strings = ComposerStrings(),
            notice = { },
        )
    }

    @Test
    fun `a draft written by another owner lands in the open composer`() = runTest(UnconfinedTestDispatcher()) {
        val drafts = MemoryDrafts()
        val composer = controller(drafts)
        composer.bind("s1", null, null)
        assertEquals("", composer.state.value.text)

        // A share (or a recovered queue entry) writes the draft store directly.
        drafts.setDraft(
            "s1",
            ChatDraft(
                value = "shared text",
                files = listOf(AttachedTextFile("notes.txt", "text/plain", "body", 4)),
            ),
        )
        composer.applyStoredDraft()

        assertEquals("the open pane reloads the shared draft", "shared text", composer.state.value.text)
        assertEquals(1, composer.state.value.files.size)
        assertEquals(
            "the reload carries an edit id so the field repaints",
            "shared text",
            composer.state.value.edit?.text,
        )
    }

    @Test
    fun `a snippet inserts its text and a second one starts on a new line`() = runTest(UnconfinedTestDispatcher()) {
        val composer = controller(MemoryDrafts())
        composer.bind("s1", null, null)
        val template = ChatTemplate("1", "Review checklist", "Check the diff.")
        composer.setTemplates(listOf(template))
        assertEquals(1, composer.state.value.templates.size)

        composer.insertTemplate(template)
        assertEquals("Check the diff.", composer.state.value.text)
        assertEquals("the caret sits after the snippet", "Check the diff.".length, composer.state.value.caret)

        composer.insertTemplate(template)
        assertEquals("Check the diff.\nCheck the diff.", composer.state.value.text)
    }

    @Test
    fun `the slash palette lists a snippet and accepting it inserts the text`() = runTest(UnconfinedTestDispatcher()) {
        val composer = controller(MemoryDrafts())
        composer.bind("s1", null, null)
        composer.setTemplates(listOf(ChatTemplate("1", "Review checklist", "Check the diff.")))

        composer.onTextChanged("/rev", 4)
        val menu = composer.state.value.slash
        assertEquals("the snippet is first in the palette", "review-checklist", menu?.commands?.first()?.name)
        assertTrue(composer.acceptSlash())
        assertEquals("Check the diff.", composer.state.value.text)
        assertEquals(null, composer.state.value.slash)
    }

    @Test
    fun `a snippet inserted in typed text gets its own line`() = runTest(UnconfinedTestDispatcher()) {
        val composer = controller(MemoryDrafts())
        composer.bind("s1", null, null)
        composer.setText("abcdef")
        composer.onCaretMoved(3)

        composer.insertTemplate(ChatTemplate("1", "Snippet", "SNIPPET"))

        assertEquals("the typed text is not glued to the snippet", "abc\nSNIPPET\ndef", composer.state.value.text)
        assertEquals(
            "the caret stays at the end of the snippet",
            "abc\nSNIPPET".length,
            composer.state.value.caret,
        )
    }

    @Test
    fun `quoting inserts the block at the caret and leaves the caret below it`() = runTest(UnconfinedTestDispatcher()) {
        val composer = controller(MemoryDrafts())
        composer.bind("s1", null, null)
        composer.setText("hello")
        composer.onCaretMoved(5)

        composer.insertQuote("quoted line\nsecond")

        assertEquals("hello\n> quoted line\n> second\n\n", composer.state.value.text)
        assertEquals(composer.state.value.text.length, composer.state.value.caret)
    }

    @Test
    fun `usage errors show the localized hint instead of the resource key`() {
        val strings = ComposerStrings(
            commandRequiresArgs = "Usage: %1\$s %2\$s",
            usageHints = mapOf("composer_cmd_plan_arg" to "[task]"),
        )

        assertEquals("Usage: /plan [task]", strings.requiresArgs("/plan", "composer_cmd_plan_arg"))
    }
}
