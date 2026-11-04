# ompweb for Android 1.0.37

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A running chat no longer jumps to the top when it reloads a message.** When
  a chat stayed open while the agent answered, a new message could throw the
  transcript all the way to the top and you had to scroll back down. The cause
  was the live stream, not the message list. The app releases a chat's stream
  when the chat leaves the screen and re-subscribes when you come back (and it
  reconnects after a network change). While the chat was away the server may
  drop its now-idle session and start a fresh one on return, and a server
  restart starts one anyway: the **stream epoch** (the server's `streamId`)
  changes with it. The app treated that epoch as a new conversation and emptied
  the transcript while it reloaded the history from the session file — for as
  long as the request took, the chat had nothing to show, and the list forgot
  where you were reading. It now keeps the transcript on screen (exactly like
  the web client, which only resets the streaming state) and lets the reload
  replace it: **at the bottom you stay at the bottom, and if you scrolled up you
  keep the row you were reading** — including a row you jumped to from a search
  hit. A reload that fails keeps the transcript with the retry banner instead
  of an empty chat.
- **Version 1.0.37** (`versionCode` 10037).

## Still open

A stream epoch still costs one full history request and drops the reply that
was *half streamed* at that moment until the reload delivers it from the
session file (omp writes the finished message). The transcript around it no
longer disappears, and the message is not lost.

## Updating

Open **Settings → System & Updates → Android app update**, keep or set the
source, and tap **Check for updates**; the app downloads the file, verifies its
SHA-256, package name, `versionCode` and signing certificate, and hands it to
Android's installer. The app data stays. Android still asks once for the
*install unknown apps* permission if it was never granted.

## Requirements

- ompweb with `GET /api/app-info` (`"api": 1`) — the server the app was built
  against. This release needs no new server capability; it changes only how the
  app treats a new live-stream epoch.
- omp installed on the server for agent features.
- Android 8.0 (API 26) or newer.

## Release artifact

- `ompweb-1.0.37.apk`
- `ompweb-1.0.37.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.37.apk` and the companion `ompweb-1.0.37.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
