# ompweb for Android 1.0.33

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **Settings → Usage loads again.** The tab kept saying "Loading usage…" and
  never showed a report. `GET /api/usage` is the one route that scans every
  transcript on the server before it answers: a cold 7-day scan of a real
  history took 44 seconds here, and the server's own diagnostics log records
  stalls of up to 136 seconds. The app measured that scan against the 60-second
  read timeout it uses for ordinary calls, gave up, and left the progress label
  on screen — the failure was a small line beside it, so from the outside the
  tab simply never finished. The route now has its own bound (three minutes of
  silence, four minutes in total), so a working scan is waited out instead of
  being abandoned.
- **A failed scan says so.** If the scan still fails — server unreachable, a
  timeout, an error answer — the tab now shows "Could not load usage data", the
  reason, and a **Retry** button, exactly like the web UI. The progress label is
  gone from that state, so a failure can no longer look like a load that never
  ends. A timeout reads as a timeout ("the server did not answer in time")
  instead of "cannot reach the server".
- **A slow scan names its work.** While the server is reading transcripts the
  tab says **Scanning sessions…** and explains that the first scan takes a
  while, so a minute of waiting is visible work instead of an unexplained
  spinner.
- **A changed range or granularity is no longer dropped.** Picking another
  range while the previous answer was still on its way used to be ignored (the
  tab then showed the old range's numbers under the new selection). The newer
  selection now replaces the running request, and only its answer reaches the
  screen.
- **The tab recovers by itself.** If the page state is ever empty without an
  error (for example after the settings state was reset), the tab loads again
  instead of waiting for a request nobody sent. A failed load is not retried
  automatically — that is what the Retry button is for.
- **Version 1.0.33** (`versionCode` 10033).

## Still open

The wait itself is the server's: a usage report walks the transcripts
synchronously, so a cold scan blocks every other request of that server for its
duration. Making the server scan in the background is a server change and not
part of this release; the app waits long enough for the current behaviour, shows
what is happening, and fails visibly if it still cannot get an answer.

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

- `ompweb-1.0.33.apk`
- `ompweb-1.0.33.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.33.apk` and the companion `ompweb-1.0.33.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
