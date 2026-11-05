# ompweb for Android 1.0.40

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **Provider usage names its provider again, without the extra text.** Every
  account line in **Settings → Provider usage** and the sessions list's usage
  warning now starts with a small two-letter badge — `CC` for commandcode,
  `OA` for openai/openai-codex, `AN` for anthropic, `GO` for google/gemini,
  `XA` for xai, `DS` for deepseek, `OR` for openrouter, `MI` for mistral, and
  the first two letters of the id for any other provider — so an e-mail alone
  no longer has to say which account it belongs to. The badge borrows a tint
  from the active theme and keeps the monogram in the theme's text color, so
  it stays readable in every light and dark palette. It grows with the font
  size instead of clipping, and on a narrow phone the account e-mail
  ellipsizes while the badge keeps its size.
- **Aliases stay apart.** omp reports aliased providers as separate ids
  (`commandcode-a`, `commandcode-b`, ...). The badge keeps the provider's
  monogram and adds the alias letter next to it, so two accounts of one
  provider are still distinguishable. The letter comes from the id the server
  sends, not from a fixed account list.
- **The full provider id is one tap away.** Tapping or long-pressing the
  badge, and the screen reader's description, spell out the provider id
  exactly as omp reported it (for example `Provider: commandcode-a`).
- **Provider accounts in the models settings use the same marker.** The
  stored-account rows under **Settings → API Keys & Providers** show the
  badge before the account label and truncate long e-mails instead of
  pushing the Disconnect button out of the row.
- **Version 1.0.40** (`versionCode` 10040).

## Still open

The composer's usage-limit notice still shows no provider: omp's per-session
limit payload carries no account or provider id, so there is nothing to name
there. The badge's tooltip shows the provider id omp reports; when omp gives a
display label instead, the badge shows that label.

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

- `ompweb-1.0.40.apk`
- `ompweb-1.0.40.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.40.apk` and the companion `ompweb-1.0.40.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
