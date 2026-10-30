package io.github.kahme247.ompweb.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Quote formatting and the insertion into the draft at the caret. */
class QuotesTest {

    @Test
    fun everyLineOfTheQuoteGetsTheMarker() {
        assertEquals("> one\n> two", quoteBlock("one\ntwo"))
        assertEquals("> alpha\n>\n> beta", quoteBlock("alpha\n\nbeta"))
        assertEquals("one line", quoteBlock("one line").removePrefix("> "))
        assertEquals("> crlf\n> line", quoteBlock("crlf\r\nline"))
        assertEquals("trailing lines are dropped", "> kept", quoteBlock("kept\n\n\n"))
    }

    @Test
    fun anEmptySelectionInsertsNothing() {
        assertEquals("", quoteBlock("   \n"))
        val insertion = quoteInsertion("typed", 2, "")
        assertEquals("typed", insertion.text)
        assertEquals(2, insertion.caret)
    }

    @Test
    fun theQuoteGoesInAtTheCaretWithABlankLineBelow() {
        val insertion = quoteInsertion("", 0, "hello")
        assertEquals("> hello\n\n", insertion.text)
        assertEquals("the caret sits under the quote", 9, insertion.caret)
    }

    @Test
    fun textBeforeAndAfterTheCaretKeepsItsPlace() {
        // Caret after "typed ": the quote starts on a new line.
        val afterText = quoteInsertion("typed here", 6, "answer")
        assertEquals("typed \n> answer\n\nhere", afterText.text)
        assertEquals("typed \n> answer\n\n".length, afterText.caret)

        // Caret at the start of an existing draft: no extra blank line above.
        val atStart = quoteInsertion("rest", 0, "answer")
        assertEquals("> answer\n\nrest", atStart.text)
        assertEquals("> answer\n\n".length, atStart.caret)

        // Caret already on a fresh line: no duplicate newline.
        val onLine = quoteInsertion("intro\n", 6, "answer")
        assertEquals("intro\n> answer\n\n", onLine.text)
    }
}
