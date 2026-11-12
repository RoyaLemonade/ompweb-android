# ompweb for Android 1.0.41

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A sent message stops saying "Sending…" once the chat has it.** The
  optimistic bubble under a prompt is cleared by the transcript the server
  sends back, and that check only looked at the last four rows of the chat.
  A turn that ran a few tools put the prompt itself further up, so the bubble
  kept its "Sending…" line under a message that had already been answered —
  most visibly after the app came back from the background or the stream
  reconnected, when the commit of the prompt arrived with the transcript
  instead of as a live event. The check now compares the whole transcript
  against what the chat held when the message was sent, so a prompt is
  recognised wherever the turn pushed it.
- **Expanded prompts and skills are recognised too.** omp rewrites some
  messages before it stores them: a `/plan` or a prompt template becomes the
  expanded instruction, and a `/skill:…` invocation is stored as a skill
  entry rather than as your message. Neither matched the typed text, so those
  sends could keep the bubble as well. Both are now settled by the entry they
  add — the same identity the web UI uses.
- **Version 1.0.41** (`versionCode` 10041).

## Still open

The bubble is the app's own optimistic row; the server's message outbox that
the web UI shows as held messages is not used by the app, so nothing on the
server can hold a message back without the app knowing.

## Updating

Open **Settings → System & Updates → Android app update**, keep or set the
source, and tap **Check for updates**; the app downloads the file, verifies its
SHA-256, package name, `versionCode` and signing certificate, and hands it to
Android's installer. The app data stays. Android still asks once for the
*install unknown apps* permission if it was never granted.

## Requirements

- ompweb with `GET /api/app-info` (`"api": 1`) — the server the app was built
  against.
- omp installed on the server for agent features.
- Android 8.0 (API 26) or newer.

## Release artifact

- `ompweb-1.0.41.apk`
- `ompweb-1.0.41.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.41.apk` and the companion `ompweb-1.0.41.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
