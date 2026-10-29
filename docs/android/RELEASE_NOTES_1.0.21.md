# ompweb for Android 1.0.21

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **On-device dictation keeps what you said before a pause** — talking, pausing
  and talking again deleted the first sentence and left only the second one.
  The take was built as if the recognizer's partial text always described
  everything spoken so far. On a phone it does not: the recognizer finalizes an
  utterance when the speaker stops and starts the next one from an empty
  hypothesis, so the text after the pause replaced the text before it. Each
  finalized utterance is now kept and the next one is appended to it with one
  space, and the session's final transcript replaces the take once instead of
  being appended again (no doubled words when it repeats the last partial).
- **One take lasts until you stop it** — a pause no longer ends dictation. The
  recognizer is asked for a session that outlives the pauses (segmented session
  mode on Android 13+, dictation mode for older services), so a break in
  speaking does not silently finish the take and swallow what follows. The
  strip under the field keeps its **Stop** and **Cancel**; backgrounding the app
  still commits what was heard.
- **Your own edits survive** — typing into the field during dictation keeps the
  visible text, including everything dictated before the pause, and the live
  text continues at the caret you left.
- **Tests** — the buffer (utterance after a pause kept, repeated hypothesis and
  repeated final not duplicated, blank final keeps the utterances, a user edit
  re-anchors), the view model (the Pixel sequence *speech → pause → speech*,
  and the fallback when the recognizer never answers), and the intent and
  callbacks of the recognizer adapter (segmented session, finalized partials,
  end of a segmented session).
- **Version 1.0.21** (`versionCode` 10021).

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

- `ompweb-1.0.21.apk`
- `ompweb-1.0.21.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.21.apk` and the companion `ompweb-1.0.21.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
