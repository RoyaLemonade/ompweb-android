package io.github.kahme247.ompweb.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.kahme247.ompweb.chat.Speaker
import io.github.kahme247.ompweb.chat.SpeakerRole
import io.github.kahme247.ompweb.ui.chat.SpeakerHeaderRow
import org.junit.Test

/**
 * The speaker headers at every matrix cell: the user's filled accent badge, the
 * assistant's outlined badge and a named subagent's badge. The golden pins the
 * initial's optical centre inside the circle at all font scales.
 */
class ChatSpeakerHeaderScreenshotsTest : ScreenshotTest() {

    @Test
    fun speakerHeader() = composeRule.verifyScreenshotMatrix(
        name = "chat-speaker-header",
        heightDp = 180,
    ) {
        SpeakerHeaderFixture()
    }
}

@Composable
internal fun SpeakerHeaderFixture() {
    Column(Modifier.fillMaxWidth()) {
        SpeakerHeaderRow(Speaker(SpeakerRole.You), SCREENSHOT_NOW)
        SpeakerHeaderRow(Speaker(SpeakerRole.Assistant, "deepseek"), SCREENSHOT_NOW)
        SpeakerHeaderRow(Speaker(SpeakerRole.Subagent, "Scout"), SCREENSHOT_NOW)
    }
}
