# ompweb for Android 1.0.32

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **The app stops downloading a chat nobody is looking at — the reason the
  phone burned about 10 GB of mobile data in one day.** The live session
  channel sends the **full accumulated answer** with every token and the full
  accumulated tool output with every chunk. Measured against a real server, one
  2,500-word answer was 123 MB in 78 seconds (about 1.6 MB/s) and a command
  that printed 5 MB cost 29 MB. A chat that had scrolled off the screen — or
  the whole app after the home button — kept that stream open, so a running
  agent kept filling the data plan with the screen off: at the measured rate,
  under two hours of streaming make the 10 GB.
  The app now releases the session subscription as soon as the chat leaves the
  screen or the app goes to the background, and opens it again on return.
  Notifications are unaffected: they arrive on the notification service's own
  channels, which stay connected.
- **Coming back downloads only what was missed.** Re-entering the chat pulls
  one incremental history page (`?sync=1`, just the new messages) instead of
  the whole transcript. A full transcript download happens at most once when a
  chat is opened, never again on a reconnect — a reconnect itself costs about
  200 bytes. A blocking dialog the agent asked while the chat was away is kept
  by the server and replayed with the new subscription, so the question is
  still there when the chat comes back.
- **The session list stops reloading behind the lock screen.** Every write of a
  running session's file asked the app for a full list reload (list + projects
  + home, measured at about 130 KB on a real history). With the screen off that
  ran for nobody. The reload now happens once when the app comes back.
- **Version 1.0.32** (`versionCode` 10032).

## Still open

A chat that **is** on screen still receives the full snapshot with every update:
about 1.6 MB/s while a long answer streams, about 0.6 MB/s while a tool prints
continuously. That is the server's frame format, not something the app can
avoid while it must show the live text. The remaining saving is a server-side
change: send a session's `message_update` and `tool_execution_update` to each
connection at most a few times per second (the latest snapshot wins, and the
last update is always delivered). Such a patch is prepared and measured — the
same answer would drop from 123 MB to roughly 10 MB — but it is not part of
this release.

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

- `ompweb-1.0.32.apk`
- `ompweb-1.0.32.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.32.apk` and the companion `ompweb-1.0.32.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
