# ompweb for Android 1.0.35

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **The age chips count hours, not calendar days.** *Today* and *2 days* are
  now **24 h** and **48 h**: the list shows chats whose newest message is at
  most 24 or 48 hours old, measured from now. A chat from 23:58 still belongs
  to *24 h* at 00:05, which the old calendar-day chip dropped, and a chat from
  yesterday morning is out even though it shares the calendar day. *All* and
  *7 days* keep their meaning, and the cutoff stays inclusive (exactly 24 h
  old is still in). A filter saved by 1.0.34 is carried over: a stored *Today*
  becomes *24 h*, a stored *2 days* becomes *48 h*, and the next save writes
  the new values, so nothing is silently cleared.
- **An available app update always has an install action.** Declining the
  banner (or the "Download over mobile data?" question, or Android's own
  confirmation) used to leave **Settings → System & Updates → Android app
  update** saying "Version 1.0.35 is available" with no button at all: the
  card took its action from the banner, which every decline clears. The card
  now follows the announcement itself. **Dismiss** only hides the shell
  banner for that version, **Cancel** on the mobile-data question returns to
  the install button, and a refused install returns to **Try again**. The
  **Dismiss** button in the card is shown only while the banner is actually
  visible for that version.
- **The update surfaces lead their tab.** In **System & Updates** the Android
  app update card now comes first, followed by the server's *ompweb update*
  and *omp update* notices; server settings, the Mobile app card, the Windows
  service card and About follow. Nothing else was removed or reordered inside
  the cards.
- **The settings list announces an available update.** While an app update is
  waiting, a compact **Update available** row (`v1.0.34 → v1.0.35`, action
  *Install*) sits at the very top of the settings category list, above the
  search field — on a phone, on a foldable and in the rail layout. Tapping it
  opens the Android app update card and highlights it, so the install (and any
  mobile-data or permission question) is one tap away. It appears even after
  the banner was dismissed, and disappears once the app is current.
- **Version 1.0.35** (`versionCode` 10035).

## Still open

The server sends the full accumulated answer and tool output with every
stream frame. While a chat is on screen a long answer still costs about
1.6 MB/s; that is the server's frame format (a delta/limited-snapshot protocol
is the remaining, larger change). Nothing in this release changes the wire
traffic.

## Updating

Open **Settings → System & Updates → Android app update** (now the first card,
also reachable from the **Update available** row above the settings list),
keep or set the source, and tap **Check for updates**; the app downloads the
file, verifies its SHA-256, package name, `versionCode` and signing
certificate, and hands it to Android's installer. The app data stays. Android
still asks once for the *install unknown apps* permission if it was never
granted.

## Requirements

- ompweb with `GET /api/app-info` (`"api": 1`) — the server the app was built
  against. This release needs no new server capability; the filter and update
  changes are local to the app.
- omp installed on the server for agent features.
- Android 8.0 (API 26) or newer.

## Release artifact

- `ompweb-1.0.35.apk`
- `ompweb-1.0.35.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.35.apk` and the companion `ompweb-1.0.35.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
