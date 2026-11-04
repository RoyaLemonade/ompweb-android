# ompweb for Android 1.0.36

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A chat that was read stays read.** Opening a chat cleared only the
  sidebar's own unread marker. The row's dot, the project counter and the
  **Unread** chip are the union of that marker, an unread entry in the
  notification list and a waiting input dialog, so a chat whose completion
  notification was still unread kept its dot after it had been opened and
  read — until the Notifications list happened to be opened as well. Viewing a
  chat now marks **its** notification-list entries read (optimistically, then
  on the server), including entries that only arrive while the chat is on
  screen. A notification that arrives after the chat was left, or while the
  app is in the background, still stays unread.
- **A late completion no longer brings the dot back.** A reconnecting stream
  answers with the server's running snapshot instead of the missed
  running→idle transition, and the list reload that follows a return from the
  chat can replay the same answer. The app compared "was running" with "is
  not running" and marked the chat unread again, although the newest message
  was the one the chat had already shown. The list now remembers the newest
  message time that was on screen for each chat it has opened (from the
  server's `lastMessageAt` and the transcript's own message times — never the
  device clock, and never the session file's `modified`, which omp rewrites
  when a chat is opened). A completion whose activity is not newer than what
  was read stays silent; the next genuinely new message marks the chat unread
  again.
- **A chat opened in this app counts as read even without a running frame.**
  When a list answer carries a message newer than the one that was read (the
  web UI or a terminal wrote to the chat, or the completion frame was missed),
  the row shows the dot instead of waiting for another run.
- **Moved chats follow their former ids.** omp moves a conversation to a new
  file and keeps the old ids as aliases on its notifications. An unread entry
  filed under a former id now marks the row under the new id, and opening the
  moved chat reads that entry too, instead of leaving the dot and the bell
  badge behind.
- **A failing mark-read cannot loop.** If the server refuses the read request,
  the history reloads its list; while a chat stays visible the app now tries
  each entry once per visit instead of retrying it on every reload. Leaving
  and reopening the chat retries.
- **Version 1.0.36** (`versionCode` 10036).

## Still open

Unread state that a previous app process recorded is stored as the session
ids the sidebar marked (the web's `omp-web:unread-session-ids`), not as a
per-chat read time. A session read before the app process was killed keeps its
cleared dot, but a message that arrives for it afterwards is recognised
through the notification list rather than through the list answer alone. The
server's notification history remains the cross-device record.

## Updating

Open **Settings → System & Updates → Android app update**, keep or set the
source, and tap **Check for updates**; the app downloads the file, verifies its
SHA-256, package name, `versionCode` and signing certificate, and hands it to
Android's installer. The app data stays. Android still asks once for the
*install unknown apps* permission if it was never granted.

## Requirements

- ompweb with `GET /api/app-info` (`"api": 1`) — the server the app was built
  against. This release needs no new server capability; the unread bookkeeping
  is local to the app and the existing notification-history route.
- omp installed on the server for agent features.
- Android 8.0 (API 26) or newer.

## Release artifact

- `ompweb-1.0.36.apk`
- `ompweb-1.0.36.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.36.apk` and the companion `ompweb-1.0.36.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
