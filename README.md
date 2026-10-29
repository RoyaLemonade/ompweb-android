# ompweb for Android

The native Android client for [ompweb](https://github.com/kahme247/ompweb), the
self-hosted web UI for the [omp](https://github.com/kahme247/ompweb) coding
agent. It is a full client for the web UI's feature set, written in Kotlin and
Jetpack Compose, and talks to the ompweb server running on your own computer
(or any machine you can reach).

The app is **not** a wrapper around the web page: the whole workspace is native
— sessions, streaming chat, the composer, files, Git, worktrees, notifications
and settings.

- Current release: **1.0.17** (`versionCode` 10017)
- `applicationId`: `io.github.kahme247.ompweb` (a stable id, so signed updates
  install over the previous version and keep the app data)
- Distribution: signed APKs on this repository's
  [Releases](https://github.com/RoyaLemonade/ompweb-android/releases); there is
  no Play Store listing

## Features

- **Sessions** as the workspace: list with pinned projects and sessions,
  search, branch trees, archive browser, quick switcher, per-session usage.
- **Live chat** with streaming answers, tool runs, diffs, plan/goal banners,
  subagents, todos, markdown with LaTeX and Mermaid, images and PDFs in the
  file viewer, jump-to-message and a raw transcript view.
- **Composer** with slash commands, attachments (files, images, shared text),
  dictation through the server's speech-to-text, message queue and steering,
  and word prediction.
- **Files, Git and worktrees**: explorer, viewer/editor, uploads with conflict
  handling, staged/unstaged diffs, worktree create/switch/remove.
- **Notifications with inline replies**: agents finishing, waiting for an
  answer, failing or switching model notify you; confirm/select dialogs and
  questions can be answered from the notification. Connection status is a
  foreground-service notification, not a message.
- **Settings** for the server connection, theme and font scale, providers,
  models, agents, extensions, tools, notifications and app updates.
- **Self-update** from GitHub Releases (or a custom feed), verified before
  Android installs it: SHA-256, package name, `versionCode` and signing
  certificate.
- Phone, foldable and tablet layouts; light and dark themes; English UI only.

## Requirements

- **An ompweb server that serves `GET /api/app-info`** with `"api": 1`. That
  endpoint is the server-side half of the native API, added by the upstream
  change in [kahme247/ompweb#324](https://github.com/kahme247/ompweb/pull/324).
  A stock ompweb 0.6.0 from npm does **not** have it: the app answers **Server
  too old** with the update command instead of connecting. Use a build that
  contains that change.
- **omp** installed on the server (`omp` on `PATH`, or `OMP_WEB_OMP_BIN`) for
  everything that starts an agent. Browsing and reading existing sessions works
  without it.
- **Android 8.0 (API 26) or newer.**
- A network path from the phone to the server. **Tailscale is recommended**,
  because it works from anywhere and encrypts the traffic before it leaves the
  device. A LAN address works only at home.

Plain HTTP is accepted only for addresses that resolve into your own private
network (loopback, RFC 1918, `100.64.0.0/10` for Tailscale, link-local,
`.local`, `*.ts.net`, or a single-label host name). For any other host use
`https://`.

## Install

1. Open the newest `android-v*` release:
   <https://github.com/RoyaLemonade/ompweb-android/releases>.
2. Download `ompweb-<version>.apk` and, to verify it, its companion
   `ompweb-<version>.apk.sha256`.
3. Allow your file manager or browser to install unknown apps when Android
   asks, then confirm the install. Signed updates install over the previous
   version; the app data (server address, theme, drafts) stays.
4. Open the app, enter the server address, tap **Connect**, and sign in with
   the same password the web UI uses.

The full walkthrough (first connection, notifications, update sources,
troubleshooting) is in [`docs/android/README.md`](docs/android/README.md).

### Verify the APK

Every release publishes the SHA-256 of the APK and the signing certificate
fingerprint. Compare them before installing:

```sh
# Linux / macOS, with both files in one folder
sha256sum -c ompweb-<version>.apk.sha256
shasum -a 256 ompweb-<version>.apk                      # macOS alternative

# Windows PowerShell
Get-FileHash .\ompweb-<version>.apk -Algorithm SHA256
```

With the Android SDK build-tools installed:

```sh
apksigner verify --print-certs ompweb-<version>.apk
```

The APK must be signed with this certificate:

- **Certificate SHA-256:**
  `d73354b52dc7c3f0c74623c439ddb7b52e252811c6e6b5f851c9d04b7015fc87`
- **Certificate SHA-1:** `06d9d698c6a43f643a27643618fd4f7d431d26d4`
- **Signer:** `CN=ompweb, OU=Android, O=ompweb, C=DE` (APK Signature Scheme v2)

An APK whose certificate differs was not built by this project: do not install
it.

## Build from source

Requirements: **JDK 21** and the Android SDK with platform `android-37.0` and
build-tools `37.0.0` (either installed in the SDK, or run
`sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`).

```sh
cd android
./gradlew assembleDebug        # debug APK, applicationId …ompweb.debug
./gradlew testDebugUnitTest    # JVM tests and the screenshot goldens
./gradlew lintDebug
```

`assembleDebug` produces `android/app/build/outputs/apk/debug/app-debug.apk`.
The debug build installs next to a release build
(`io.github.kahme247.ompweb.debug`).

A release build is signed only when `OMPWEB_SIGNING_PROPERTIES` points at a
properties file outside the checkout:

```properties
storeFile=/absolute/path/release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

```sh
OMPWEB_SIGNING_PROPERTIES=/path/to/signing.properties ./gradlew assembleRelease
```

Without that variable the release build is unsigned and only useful as a
compile check. Never commit the keystore or the properties file.

## Releases

Releases are built and published automatically by
[`android-release.yml`](.github/workflows/android-release.yml) when a tag
`android-v<version>` is pushed:

1. `versionName` in
   [`android/app/build.gradle.kts`](android/app/build.gradle.kts) must equal
   `<version>`; the workflow compares the tag against `aapt2 dump badging` and
   refuses to publish a mislabelled APK.
2. [`docs/android/RELEASE_NOTES_<version>.md`](docs/android) must exist; it
   becomes the release body.
3. The signing key comes from the `android-release` environment's secrets
   (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
   `ANDROID_KEY_PASSWORD`), is decoded only into `$RUNNER_TEMP`, and is deleted
   again by an `if: always()` step.
4. The APK is staged as `ompweb-<version>.apk` with a `.sha256` companion, and
   its signing certificate is checked against the fingerprint above before the
   upload. The publishing job re-checks checksum, `versionName` and certificate
   before it creates the release.

The in-app updater reads this repository's releases: `owner/repo` is the single
`updateReleasesRepo` constant in `android/app/build.gradle.kts`, injected as
`BuildConfig.UPDATE_RELEASES_REPO`, and both the release source and the URL
allow-lists derive from it. See
[`docs/android/README.md`](docs/android/README.md#releasing).

## Privacy

- **No telemetry.** No analytics, crash reporting or third-party SDK. The app
  talks to your ompweb server, and for an update check to the source you
  selected (`api.github.com` or your custom feed).
- **The password is never stored or logged.** Only the session cookie is kept,
  encrypted with an AES-256-GCM key in the Android Keystore.
- **The cookie stays bound to one origin** (scheme, host and port that set it).
- **No cloud backup**: `allowBackup="false"` plus backup/data-extraction rules
  keep app data out of Google backup and device transfer.
- **Links** open in a Custom Tab, only for `http(s)`/`mailto`.
- **Self-updates are verified** (SHA-256, package name, `versionCode`, signing
  certificate) before Android sees the file.

## License

MIT — see [LICENSE](LICENSE). The upstream ompweb project is MIT-licensed as
well; this repository only contains the Android client and its documentation.

Security reports: see [SECURITY.md](SECURITY.md).
