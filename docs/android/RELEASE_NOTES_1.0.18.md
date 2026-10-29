# ompweb for Android 1.0.18

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **Projects are ordered by what you are doing** — the sessions list starts
  with pinned projects, then projects with a running chat (this server or
  another omp process), then everything else by the newest session in the
  project. Chats inside a project still sort newest first.
- **Pin a project** — the project row's menu has *Pin project* / *Unpin
  project*. Pins are stored per server origin, exactly like pinned sessions, and
  the most recently pinned project leads the pinned group. Signing out,
  switching servers or a rejected session forgets them; hiding a project drops
  its pin.
- **The list never jumps under your finger** — a recomputed order is held back
  while you scroll, touch the list, keep a row menu open or type in the search
  field, and is applied 1.5 s after the interaction ends. The decision is a pure
  `ProjectOrderHold` with JVM tests, so the freeze and settle rules are pinned
  without an emulator.
- **Manual drag reordering is gone** — the long-press drag, the *Move up* /
  *Move down* menu items and the `sortOrder` PATCH are removed from the app.
  Pins replace the manual order; the server's project registry is not touched.
- **Tests** — `ProjectOrderingTest` (pins > running > newest activity, stable
  ties, casing-only path matches, malformed timestamps),
  `ProjectOrderHoldTest` (freeze during interaction, settle window, restart),
  `SettingsStorePinnedProjectsTest` (per-origin round-trip through DataStore,
  clear on sign-out, session pins untouched) and the view-model pin tests
  (origin scoping, newest pin first, unpin clears the origin entry, hiding a
  project forgets its pin).
- **Version 1.0.18** (`versionCode` 10018).

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

- `ompweb-1.0.18.apk`
- `ompweb-1.0.18.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.18.apk` and the companion `ompweb-1.0.18.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
