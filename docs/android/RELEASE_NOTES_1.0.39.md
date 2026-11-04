# ompweb for Android 1.0.39

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A tapped session notification now always lands on that session.** Tapping
  a notification while the app was not running could open the last chat or the
  composer instead of the chat the notification named: the cold-start restore
  ran in the same frame as the tap and reopened its saved view on top of it.
  The tap now wins, the restore is settled without opening anything. A tapped
  notification also closes the Settings and Notifications surfaces when
  Android restored them together with the process — the chat no longer opens
  hidden behind a dialog that was still open. And the intent that opens a chat
  now names its target in its data URI: before, two notifications relied only
  on a hash code to stay apart, so a collision could have replaced one chat's
  id with another's. The per-installation key behind the tap check is written
  to disk as soon as the app starts, so a notification posted just before the
  process died can no longer keep an unverifiable token.
- **Unanswered questions come back every two hours.** When a question or
  approval stays unanswered, the server re-publishes its notification with a
  **Reminder** title ("Erinnerung" on German devices) and the time it has been
  waiting. The reminder replaces the original notification under the same tag
  and sounds/vibrates again — Android only suppresses a repeat when it is
  posted as an update-only alert. It stops the moment the dialog is answered
  anywhere, cancelled, expires, or its session ends, and it never comes back
  more than twelve times.
- **New switch: Remind me about unanswered questions** (on by default) in
  **Settings → Notifications**. It is stored with this device's notification
  settings and sent to your server, so a phone that turns it off gets no
  reminders while the first question notification is unchanged. Older servers
  ignore the field, so the switch is harmless there. Search finds the row.
- **Version 1.0.39** (`versionCode` 10039).

## Still open

The reminder is a repeat, not a queue: if Android blocks the app's channel or
notifications entirely, neither the first notification nor its reminder can be
shown.

## Updating

Open **Settings → System & Updates → Android app update**, keep or set the
source, and tap **Check for updates**; the app downloads the file, verifies its
SHA-256, package name, `versionCode` and signing certificate, and hands it to
Android's installer. The app data stays. Android still asks once for the
*install unknown apps* permission if it was never granted.

## Requirements

- ompweb with `GET /api/app-info` (`"api": 1`) — the server the app was built
  against. The reminder needs a server that publishes `input` reminders; with
  an older server the new switch is stored and ignored.
- omp installed on the server for agent features.
- Android 8.0 (API 26) or newer.

## Release artifact

- `ompweb-1.0.39.apk`
- `ompweb-1.0.39.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.39.apk` and the companion `ompweb-1.0.39.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
