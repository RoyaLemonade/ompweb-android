# ompweb for Android 1.0.30

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **"Last used" means the last message again.** A chat that you merely opened —
  or that had to reload — claimed to have been used "just now": omp rewrites the
  session file on every resume, and the list read that write as conversation
  activity. The row's time, the details card's **Updated** field, the **Age**
  chips and the project order now follow the newest message in the chat, so an
  opened chat keeps its real time. A session this phone never opened still
  shows the server's timestamp.
- **Every message row says when it was sent.** The row under a message
  (Copy, Read, …) now ends with a muted time — the clock time for today,
  otherwise the short date and time — for your messages and for the assistant's.
  Tap it to see the full date and time, exactly like the stamp next to the
  speaker.
- **Version 1.0.30** (`versionCode` 10030).

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

- `ompweb-1.0.30.apk`
- `ompweb-1.0.30.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.30.apk` and the companion `ompweb-1.0.30.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
