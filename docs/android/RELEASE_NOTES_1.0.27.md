# ompweb for Android 1.0.27

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A filter bar for the session list.** Three chips above the list narrow a
  long workspace: **Running**, **Unread** and **Age** (`All`, `Today`,
  `2 days`, `7 days`, based on the session's last activity). The chips combine,
  so Running + Unread + 2 days shows exactly the chats that need attention
  now.
- **Unread means "wants attention"**: the sidebar's unread dot, an unread entry
  in the notification list, or a blocking input dialog that waits for an
  answer. The same rule drives the row badges and the project counters, so the
  filter and the dot agree.
- **Pins ignore the filters.** Pinned sessions keep their shortcut at the top
  and pinned projects keep their row; a project whose sessions all fail a chip
  collapses instead. A small row at the bottom says how many sessions are
  hidden and offers **Clear filters**; the empty state does the same when
  everything is filtered out.
- **The choice is remembered per server** (Running, Unread and the age preset)
  and forgotten on sign-out or a server switch, like the pins.
- **Version 1.0.27** (`versionCode` 10027).

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

- `ompweb-1.0.27.apk`
- `ompweb-1.0.27.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.27.apk` and the companion `ompweb-1.0.27.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
