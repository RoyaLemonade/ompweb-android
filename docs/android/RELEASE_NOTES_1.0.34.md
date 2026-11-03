# ompweb for Android 1.0.34

The native Android client for ompweb: sessions, the live chat, the composer,
files, Git and worktrees, notifications with inline replies, and the settings
screens, backed by your own ompweb server.

## What changed

- **Opening a chat no longer downloads the transcript twice.** The session
  header request (`GET /api/sessions/{id}`) also carried the whole display
  context — measured 1.3 to 4.8 MB on a real history — while the chat only
  reads the branch tree from it. The app now asks for the metadata shape
  (`?meta=1`, about 60–300 KB) and the transcript once, from the endpoint that
  is meant for it. The details dialog reads model, thinking level and the
  newest message time from that metadata answer, and only “Copy transcript”
  asks for the full body — the copy is the one action that needs every
  message. Against an older server the app still works: it ignores the
  parameter and answers the full body as before.
- **An unchanged chat is revalidated instead of re-downloaded.** The app keeps
  the `ETag` of the last context answer together with its offline cache. When
  the session file and every view option (branch, compaction setting) are
  unchanged, the server answers `304` and the cached transcript is shown —
  roughly 200 bytes instead of 1.2–3.0 MB per open. A `304` without a cached
  body is refetched without the header, so a validator can never paint an
  empty chat.
- **The model catalog is fetched once, not on every chat switch.** `/api/models`
  is ~310 KB for a real configuration. It is now kept per server address and
  working directory for an hour, an explicit save in *Settings → Providers &
  models* clears it immediately, and a logout or server switch drops it (the
  same cwd on another server must not offer the old installation's models).
- **The session list only reloads while it is on screen.** A running agent
  writes its session file constantly, and every write refreshed list,
  projects and home (~130 KB). With a chat open on a phone that answer was
  never read; the app now marks the list stale and reloads once when the list
  is visible again. The same already applied behind the lock screen.
- **Live-state polls are lighter and rarer.** The 14 KB `get_state` answer was
  13 KB system prompt; polls and reconcile now ask for the state without it
  (`?systemPrompt=0`), and the system-prompt panel requests it explicitly.
  The busy reconcile poll moved from 15 s to 30 s — the live stream remains
  authoritative.
- **Presence stops behind the lock screen.** When the server advertises
  `presenceOptional` (a native client is reached through its open stream), the
  45 s presence beat runs only while a chat is visible in the foreground; going
  to the background sends one "not visible" report and then stops. A report
  that a dead spot swallows is retried every 10 s until it arrives (or the app
  comes back), so a lost POST cannot leave the server suppressing that chat's
  notifications for a minute. That removes about 1,920 radio wakeups a day and
  the associated battery cost. A server without the flag keeps the old
  behaviour, whose retry now stays inside the server's 60 s presence TTL
  instead of expiring the entry for 15 s. The notification stream now also
  names the device, so the server can route in-page deliveries through *that*
  device's preferences.
- **Usage tab: no scan storm.** Changing the range or granularity no longer
  starts one full server scan per tap — the selection is debounced, and a
  superseded scan is cancelled on the wire instead of being left for the
  server to finish.
- **Updates respect mobile data.** On a metered connection the APK download
  asks first ("Download over mobile data? About 6 MB"), and the question can
  be declined from Settings too. When Android still has to ask for the
  *install unknown apps* permission, the mobile-data yes is kept, so coming
  back does not ask a second time. Wi-Fi is unchanged. The foreground update
  check now reuses its answer for six hours instead of 30 minutes; the manual
  button still forces a check.
- **The updater trusts only the release key.** The app pins the SHA-256 of its
  release signing certificate and refuses any downloaded APK signed with a
  different key, in addition to the installed-signer check Android applies. A
  debug build cannot install updates at all.
- **Bug fixes from the review:** the goal banner keeps omp's `budget-limited`
  state visible (as "Goal budget reached", with Drop but no Pause), goal
  responses are fenced to their session (a slow pause/create reply can no
  longer paint the next chat), project rows fall back to the sessions' own
  workspaces while `/api/projects` has not answered (and "No sessions" waits
  for that answer instead of appearing first), a logout can no longer resurrect
  forgotten session activity from a racing file read, the stale duplicate
  `visibleSessionEntries` overload is gone, "Copy transcript" copies the whole
  conversation again (it had followed the new metadata request and produced a
  file without a single message), the details dialog keeps model and thinking
  level (from the metadata answer, or from the full body of an older server),
  and the list now uses the server's `lastMessageAt` (newest message) for its
  time, age chip and ordering so a message written by another client sorts
  correctly — the dialog shows the same value.
- **Version 1.0.34** (`versionCode` 10034).

## Still open

The server sends the full accumulated answer and tool output with every
stream frame. While a chat is on screen a long answer still costs about
1.6 MB/s; that is the server's frame format (a delta/limited-snapshot protocol
is the remaining, larger change). Everything above is the app's own share of
the traffic and wakeups.

## Updating

Open **Settings → System & Updates → Android app update**, keep or set the
source, and tap **Check for updates**; the app downloads the file, verifies its
SHA-256, package name, `versionCode` and signing certificate, and hands it to
Android's installer. The app data stays. Android still asks once for the
*install unknown apps* permission if it was never granted.

## Requirements

- ompweb with `GET /api/app-info` (`"api": 1`) — the server the app was built
  against. The new capabilities (`sessionMeta`, `contextEtag`,
  `stateSystemPromptOptional`, `presenceOptional`) are used when present and
  the app keeps working with a server that does not advertise them.
- omp installed on the server for agent features.
- Android 8.0 (API 26) or newer.

## Release artifact

- `ompweb-1.0.34.apk`
- `ompweb-1.0.34.apk.sha256` accompanies the file; the release also publishes
  the APK's size and signing certificate.

## Install

1. Download `ompweb-1.0.34.apk` and the companion `ompweb-1.0.34.apk.sha256`
   from the release.
2. Allow your file manager or browser to install unknown apps.
3. Install the APK. Signed updates install over an older version and keep the
   app data.

Full instructions: [`docs/android/README.md`](README.md).
