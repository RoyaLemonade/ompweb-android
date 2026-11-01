package io.github.kahme247.ompweb.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.kahme247.ompweb.R
import io.github.kahme247.ompweb.chat.AssistantBlock
import io.github.kahme247.ompweb.chat.ChatMessage
import io.github.kahme247.ompweb.chat.ChatState
import io.github.kahme247.ompweb.chat.TranscriptRow
import io.github.kahme247.ompweb.ui.theme.OmpWebTheme
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The action row under a message ends with the time the message was sent (user
 * and assistant alike). Tapping it opens the full date, exactly like the
 * speaker header's stamp. No speaker header is rendered here, so the only
 * `10:00 AM` in the tree is the row's own stamp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h890dp-mdpi", application = android.app.Application::class)
class MessageRowTimeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val now: Long = 1_793_016_000_000L // 2026-10-26 12:00 UTC
    private val sentAt: Long = now - 2 * 3_600_000L // 10:00 the same day

    init {
        // The rendered stamp must not depend on the host's locale or zone.
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun render(message: ChatMessage) {
        val state = ChatUiState(
            engine = ChatState(messages = listOf(message)),
            preferences = ChatPreferences(),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalChatClock provides { now }) {
                OmpWebTheme {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        ChatRow(
                            row = TranscriptRow.Message(message),
                            messages = listOf(message),
                            state = state,
                            liveTail = false,
                            indexByKey = mapOf(message.key to 0),
                            callbacks = ChatRowCallbacks(
                                onLink = {},
                                onFork = { _, _ -> },
                                onLoadThinking = { _, _ -> },
                                onToggleSpeech = { _, _, _ -> },
                                onStopSpeech = {},
                                onLoadBashOutput = {},
                                onDownloadBashOutput = {},
                                onEditFromHere = { _, _ -> },
                                onQuote = {},
                                onShare = {},
                            ),
                        )
                    }
                }
            }
        }
    }

    private val user = ChatMessage.User(
        key = "u",
        timestamp = sentAt,
        entryId = "e-u",
        text = "Which file holds the composer?",
    )

    private val assistant = ChatMessage.Assistant(
        key = "a",
        timestamp = sentAt,
        entryId = "e-a",
        blocks = listOf(AssistantBlock.Text("ComposerBar.kt.")),
        model = "claude-sonnet-4",
        provider = "anthropic",
    )

    @Test
    fun `a user message shows the time it was sent`() {
        render(user)

        composeRule.onNodeWithText("10:00 AM").assertExists()
    }

    @Test
    fun `an assistant message shows the time it was sent`() {
        render(assistant)

        composeRule.onNodeWithText("10:00 AM").assertExists()
    }

    @Test
    fun `tapping the row time opens the full date`() {
        render(user)

        composeRule.onAllNodesWithText("10:00 AM").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("October 26, 2026, 10:00 AM").assertExists()
        val title = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.chat_timestamp_full_title)
        composeRule.onNodeWithText(title).assertExists()
        assertTrue("the dialog title is a real label", title.isNotBlank())
    }
}
