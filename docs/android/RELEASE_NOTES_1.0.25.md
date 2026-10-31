# ompweb for Android 1.0.25

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **`/goal` now starts omp's real goal mode** — the command is sent as omp's
  `goal` RPC (`op: "create"`) instead of a plain prompt with a client-side
  banner. The goal objective carries a fixed plan template: the agent must
  first write **Key points**, **No-gos** and **Stop conditions** (each one
  objectively verifiable with evidence), keep them as todos, and work until
  every stop condition is proven before it completes the goal. Optional lines
  after the aim — `No-gos:` and `Stop conditions:` — are passed into the same
  plan.
- **The goal banner is omp's state, not the app's** — objective, active or
  paused status, elapsed time and token budget come from `get_state.goal` and
  live `goal_updated` events. **Pause**, **Resume** and **Drop** call the
  matching RPC ops. Stopping a run pauses the goal in omp, and the banner then
  offers **Resume**. The old per-session goal storage is removed.
- **Older servers keep working** — a server or omp that answers “Unknown/
  Unsupported command: goal” gets the same plan template as a normal prompt
  and a notice explains the fallback, so the aim is never lost.
- **New sessions** use the same path: `/goal` in the new-session composer
  starts goal mode on the freshly created session.
- **Version 1.0.25** (`versionCode` 10025).

## Updating

Open **Settings → System & Updates → Android app update**, keep or set the
source, and tap **Check for updates**; the app downloads the file, verifies its
SHA-256, package name, `versionCode` and signing certificate, and hands it to
Android's installer. The app data stays. Android still asks once for the
*install unknown apps* permission if it was never granted.

## Requirements

- ompweb with `GET /api/app-info` (`"api": 1`) — the server the app was built
  against.
- Goal mode needs an ompweb server that forwards the `goal` RPC command and an
  omp build with goal support (18.x). Without it the app falls back to a
  normal prompt with the same plan instructions.
- omp installed on the server for agent features.
- Android 8.0 (API 26) or newer.

## Release artifact

- `ompweb-1.0.25.apk`
- `ompweb-1.0.25.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.25.apk` and the companion `ompweb-1.0.25.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
