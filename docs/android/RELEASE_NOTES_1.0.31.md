# ompweb for Android 1.0.31

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **The app is quiet while you are not looking at it.** A chat that is open
  behind the lock screen used to keep asking the server for its state — every
  15 s while an agent ran, every 2 s when another omp held the session, and up
  to every minute while the state was unknown — and the "tokens per second"
  display repainted twice a second on top. Both now belong to a visible screen:
  in the background the transcript still updates from the live stream and
  notifications still arrive through the service, and the first thing that
  happens when you come back is one immediate catch-up. Leaving the chat (back
  to the list) ends its polling as well, instead of polling a chat nobody sees.
  They come back the moment the chat is on screen again.
- **Reconnects slow down instead of hammering a server that is off.** With the
  PC switched off or the VPN gone, the app used to reconnect every 30 s
  forever, each attempt spending up to ten seconds on the radio. The delay now
  keeps growing to five minutes while the app is in the background; in the
  foreground it still stops at 30 s. Coming back to the app, or a network
  change (Wi-Fi back, VPN up), retries immediately.
- **Fewer "I'm still here" reports.** The presence report that tells the
  server which chat you are reading was sent every 30 s, even when nothing had
  changed. It now goes out immediately on every change (opening or leaving a
  chat, going to the background, coming back) and is otherwise refreshed every
  45 s, only while the notification service runs and the connection is up.
  That is 1,920 instead of 2,880 reports a day. Notifications are unaffected:
  the server addresses this device's in-app deliveries through that entry, so
  it cannot be dropped while notifications are on — see "Still open" below.
- **Version 1.0.31** (`versionCode` 10031).

## Still open

The audit's target of zero traffic in the background is not reachable from the
app alone: this server routes in-app notifications for devices without Web Push
through the presence entry, and the native app has no push subscription. The
keep-alive therefore stays, just slower — and a report that fails is repeated
after 30 s so a single lost report does not leave the phone unreachable.
Separately, unchanged from 1.0.30: the "last used" time of a chat this phone
never opened still follows the server's session file, only a chat opened here
reports its newest message. The rest of the saving needs a protocol change
(address a reachable subscription by device id, or real push delivery).

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

- `ompweb-1.0.31.apk`
- `ompweb-1.0.31.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.31.apk` and the companion `ompweb-1.0.31.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
