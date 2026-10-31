# ompweb for Android 1.0.28

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A filter no longer keeps every project open.** With `Age: Today` (or any
  other chip, or an active search) the project headers now collapse and expand
  again when you tap the chevron: a collapsed project shows its header with the
  activity count and keeps its chats hidden. Before, the filter forced every
  project expanded, so the toggle appeared to do nothing.
- **The five-row limit stays.** A project with more matching sessions still
  shows five rows and a **Show more (n)** action; the filter no longer lifts
  that cap or hides the action.
- **Version 1.0.28** (`versionCode` 10028).

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

- `ompweb-1.0.28.apk`
- `ompweb-1.0.28.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.28.apk` and the companion `ompweb-1.0.28.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
