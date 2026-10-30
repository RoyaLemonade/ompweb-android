# ompweb for Android 1.0.23

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A queued message is delivered when the chat is opened** — the offline
  outbox held messages back whenever a state read for the same session was
  already on the wire, which is exactly what opening a chat and reconnecting
  do. The queue then showed "Waiting for connection" over a healthy connection
  until the chat was opened a second time. Delivery now waits for that read's
  answer (bounded) instead of giving up on it, and a poll that finally learns
  the session's state starts the queue again.
- **A refused message no longer blocks the queue** — a 4xx/5xx answer to a
  queued message was treated like a transport problem: the record went back to
  the queue and was re-posted on every reconnect, repeating the refusal
  forever and keeping every later message of that chat behind it. An answer is
  now final: the message leaves the queue, its text and images go back into
  the input field, and the server's reason is shown. The next queued message
  goes out immediately.
- **A failed steer/follow-up no longer stops the run it never started** — a
  queued message delivered into a running session rolled back the *server's*
  turn when the steer was refused: the streaming answer vanished, the footer
  showed idle and the stop button disappeared until the next frame. That path
  now only reports the error; only a message that became this app's own prompt
  is rolled back on failure.
- **The queue file survives a crash** — the record file is written with `fsync`
  and moved over the old one in a single `rename`, instead of deleting
  `queue.json` first: a process death in that window used to lose the whole
  queue. Unreadable records (empty session, no text and no image) are dropped
  together with their images instead of living on unseen, and a message the
  app could not deliver for a month is dropped with its images, so a lost
  connection cannot fill app storage forever.
- **A queue that cannot be written says so** — a failed "this message may be on
  its way" write stopped being read as "the message is gone" (which re-read the
  same record forever); it stops the loop with a notice and keeps the message
  queued. A failed removal keeps the message's images, because the record is
  still there. Signing out cancels the chat surface and its delivery loop
  before the queue directory is wiped.
- **A snippet inserted in typed text gets its own line** — inserting at the
  caret no longer glues the snippet's last word to the text behind it.
- **Version 1.0.23** (`versionCode` 10023).

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

- `ompweb-1.0.23.apk`
- `ompweb-1.0.23.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.23.apk` and the companion `ompweb-1.0.23.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
