# ompweb for Android 1.0.20

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **Auto dictation no longer uploads to a server that cannot transcribe** —
  with the engine *Auto*, tapping the mic recorded and uploaded the audio
  whenever a server address was configured. A server without
  `OMP_WEB_STT_ENDPOINT` or `OMP_WEB_STT_MODEL` answers that upload with
  **501 STT not configured**, and `/api/app-info` has no capability flag for
  speech-to-text, so the app was treating "a server exists" as "the server can
  transcribe". Auto now asks the server itself before it opens the
  microphone: a small `POST /api/stt` without audio tells it whether the
  route is configured (the server checks its STT settings before it reads the
  audio), and the answer is cached per server address, refreshed at app start
  and after a server switch. Only a positive answer sends a recording to the
  server; otherwise the phone's installed recognizer takes the take.
- **A take is never lost when the server still refuses** — if an upload is
  answered with 501 in Auto mode, the server is remembered as "no STT", the
  recording stays on the phone (playable), and the deck says why with a
  one-tap **Dictate on this phone instead**. The recap is honest: the phone's
  recognizer cannot reuse recorded audio, so that button starts a new take.
  The next Auto attempt goes straight to the phone.
- **Explicit "Server" keeps working as chosen** — an explicit server engine
  still uploads, keeps its retry and shows the server's own message, even
  after other composers learned that this server cannot transcribe.
- **Tests** — the engine decision matrix (configured / unconfigured / not yet
  asked, with and without an on-device model), the per-server cache (one probe
  per address, unknown answers retried, a failed probe not cached), the
  configuration probe's HTTP mapping (501, `missing_audio_file`, signed out,
  5xx, no route), and the view model's 501 fallback (local copy kept, no
  re-upload, on-device take on request, next Auto take on-device) together
  with the explicit-server path.
- **Version 1.0.20** (`versionCode` 10020).

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

- `ompweb-1.0.20.apk`
- `ompweb-1.0.20.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.20.apk` and the companion `ompweb-1.0.20.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
