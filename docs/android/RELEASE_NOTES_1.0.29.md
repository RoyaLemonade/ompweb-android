# ompweb for Android 1.0.29

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **Search finds every match again.** 1.0.28 stopped a filter from holding
  every project open, but it also applied that rule to the search field: a
  query no longer opened a collapsed project, so matches inside it stayed
  invisible, and a project with more than five matching sessions hid the rest
  behind **Show more**. A search term now opens every project with a match and
  lists all of its matches; the field is a result set, not a filter.
- **Your collapse choices survive the search.** The app does not rewrite the
  stored expand/collapse state while you search. Clear the field and each
  project is folded exactly as you left it.
- **Chips keep their 1.0.28 behaviour.** `Running`, `Unread` and the age
  presets still only narrow the rows: the chevron stays in charge and the
  five-row **Show more** limit still applies, so a chip does not force a
  collapsed project open.
- **Version 1.0.29** (`versionCode` 10029).

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

- `ompweb-1.0.29.apk`
- `ompweb-1.0.29.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.29.apk` and the companion `ompweb-1.0.29.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
