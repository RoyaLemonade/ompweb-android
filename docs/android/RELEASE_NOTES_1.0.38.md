# ompweb for Android 1.0.38

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A failed reload after a reconnect is shown again.** Since 1.0.37 a chat
  keeps its messages on screen while it reconnects its live stream and
  reloads the transcript in the background. If that reload failed (for
  example on a weak connection), the chat silently kept the older rows with
  no hint. It now shows the error banner with **Retry** above the kept
  messages, as it does for a cached transcript that could not be refreshed.
