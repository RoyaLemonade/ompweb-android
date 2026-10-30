# ompweb for Android 1.0.24

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **No notification for the chat you are reading** — three gaps in the
  on-screen rule are closed. The presence report (visible + session id) is now
  sent even while the notification service is still activating, its visibility
  and its sequence number are taken under one lock (a report can no longer
  carry an older view with a newer sequence and win on the server), and the
  app's own guard compares the same ids as the server: a session omp moved to a
  new file still counts as the chat on screen under its former id. Opening or
  re-reporting that chat also clears a notification that slipped in between,
  even when the selected id did not change.
- **A finished chat is one notification, not two** — every completion used to
  add a project summary next to its own notification, so a single finished
  chat already showed two entries. The summary now appears only when it really
  summarizes two or more chats of the project ("3 chats finished") and carries
  their names; a single chat is its own notification.
- **Finished chats are quiet by default** — the completed notification no
  longer plays a sound or vibrates. New setting **Settings → Notifications →
  Sound/vibration for finished chats** (off by default; searchable as
  "Sound/vibration for finished chats"). Questions and errors keep their loud
  alert and their vibration patterns, the **Vibrate** setting still decides
  whether an alert pulses (a finished chat then plays its sound without one),
  and the existing per-type switches still apply.
- **A burst alerts once** — a project's first completion alerts (when the new
  setting is on), every further completion in the next 20 seconds is posted
  silently and folded into the summary line for that project. Answering a
  finished chat's notification ("Sent" / "Failed") also stays silent.
- **Version 1.0.24** (`versionCode` 10024).

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

- `ompweb-1.0.24.apk`
- `ompweb-1.0.24.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.24.apk` and the companion `ompweb-1.0.24.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
