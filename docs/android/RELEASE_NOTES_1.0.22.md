# ompweb for Android 1.0.22

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **A phrase you say twice is no longer swallowed** — on-device dictation
  dropped any utterance that began with the words it had already committed:
  after "ok", saying "ok weiter" left only "weiter", and a repeated "ja" in
  "ja … ja genau" disappeared. The buffer stripped everything up to the length
  of the committed text, because it assumed the recognizer was re-sending the
  session so far. It is not: a hypothesis and a finalized utterance belong to
  the utterance the recognizer is in right now, so they are now taken as they
  arrive. Nothing is compared against the committed text any more — the words
  the user said are the words that stay.
- **The one real re-send is still counted once** — a service that reports the
  same finalized utterance on both of its paths (`onSegmentResults` on
  Android 13+ and the `final_result` partial for services without a segmented
  session) delivers it twice; the buffer recognizes that by the callback that
  delivered it, not by matching words, so the take still holds the utterance
  exactly once. The session's final transcript keeps replacing the take when
  it repeats the utterances, and a blank final keeps all of them.
- **Tests that wait no longer fail on a loaded machine** — every test class
  that polled with its own deadline (eight to ten seconds) now uses one shared
  helper with a 60-second safety net that names the awaited condition and the
  observed state when it fails, plus a flow-based variant for state a test can
  observe directly. No test was weakened and none retries: a passing run still
  returns the moment the condition holds.
- **Version 1.0.22** (`versionCode` 10022).

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

- `ompweb-1.0.22.apk`
- `ompweb-1.0.22.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.22.apk` and the companion `ompweb-1.0.22.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
