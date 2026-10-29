# ompweb for Android 1.0.19

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **Dictation on the phone works again** — tapping the mic with the engine
  *On this phone* (or *Auto* while the server reports no transcription
  service) could answer **No speech detected** half a second after the tap,
  before you had said a word. The app had asked Android for a 500 ms *minimum
  input length*, and the on-device recognizer ends the take as soon as that
  much audio has arrived: the microphone closed at 500 ms and the take failed
  as if the microphone had heard silence. The intent no longer carries that
  endpointer extra; the take ends after you pause (the 1.5 s complete-silence
  window stays) or when you tap **Done**.
- **A missing language pack is now a model download, not silence** — before
  listening, the app asks the recognizer which on-device model it has for the
  picked language (Android 13+). A missing model is downloaded through
  Android's own download service and the message says so, instead of
  surfacing as *No speech detected*. A language this phone cannot recognize
  on-device, and a language without an installed pack that cannot be
  downloaded, each get their own message naming the language.
- **The real cause is named, not "No speech"** — the recognizer's error codes
  are mapped one by one: missing permission, missing model, downloading model,
  unsupported language, busy recognizer, too many requests, a recognizer that
  never started, an unreadable microphone, and a take that the recognizer
  abandoned before it could hear anything. Silence is only reported as
  silence after a real listening window.
- **An empty or malformed language never reaches the recognizer** — the
  resolved tag is always a BCP-47 tag (the device locale when *Device
  language* is selected); a blank tag is refused with a message instead of
  being sent to a service that cannot match it.
- **The READMEs name no version** — `README.md` and `docs/android/README.md` no
  longer carry the current version number, the APK file name or a checksum;
  they point to the release page and the release body, which the release
  workflow generates. The stable signing-certificate fingerprint stays. A
  release now needs no documentation edit.
- **Tests** — `AndroidOnDeviceSttTest` (Robolectric + the platform's fake
  recognizer: the intent carries no premature-end extra, still carries the
  BCP-47 language, prefer-offline and partial results; the model query starts,
  downloads or refuses with the matching error; a language service that
  reports nothing still gets to listen; the pre-Android-13 path listens
  directly; each error code maps to its own error; an instant give-up is not
  "no speech" while a real listening window is), plus the view-model tests for
  the new messages.
- **Version 1.0.19** (`versionCode` 10019).

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

- `ompweb-1.0.19.apk`
- `ompweb-1.0.19.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.19.apk` and the companion `ompweb-1.0.19.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
