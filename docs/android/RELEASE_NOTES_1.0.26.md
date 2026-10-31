# ompweb for Android 1.0.26

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **Hide now hides.** Hiding a project from its row menu in the sessions list
  left the row on screen: the server correctly marked the project as hidden and
  dropped it from `GET /api/projects`, but the app rebuilt a project row from
  the sessions of that project — and hiding deliberately keeps those sessions
  on disk. The list now follows the server's project list; a hidden project and
  its sessions leave the sidebar until the project is added again.
- **Live rows keep their workspace.** A session started from the app is
  listed a moment before its first message is written to disk. That
  placeholder still gets its own project row while the server's project list
  cannot know the directory yet — only file-backed sessions no longer create
  rows of their own.
- **Version 1.0.26** (`versionCode` 10026).

## Updating

Open **Settings → System & Updates → Android app update**, keep or set the
source, and tap **Check for updates**; the app downloads the file, verifies its
SHA-256, package name, `versionCode` and signing certificate, and hands it to
Android's installer. The app data stays. Android still asks once for the
*install unknown apps* permission if it was never granted.

## Requirements

- ompweb with `GET /api/app-info` (`"api": 1`) — the server the app was built
  against. Hiding a project needs the server's registry (ompweb 0.6.0 or newer).
- omp installed on the server for agent features.
- Android 8.0 (API 26) or newer.

## Release artifact

- `ompweb-1.0.26.apk`
- `ompweb-1.0.26.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.26.apk` and the companion `ompweb-1.0.26.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
