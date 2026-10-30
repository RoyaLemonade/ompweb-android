package io.github.kahme247.ompweb.ui.chat

import android.content.Context
import android.content.Intent
import io.github.kahme247.ompweb.R

/**
 * Open the Android share sheet with [text] (a message's body or the text of a
 * quote). Uses the same `FLAG_ACTIVITY_NEW_TASK` chooser as the other share
 * entry points (`FileTransfer`, `RemoteImage`), so it also works from a
 * non-activity context.
 */
fun shareText(context: Context, text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, context.getString(R.string.chat_share_chooser))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}
