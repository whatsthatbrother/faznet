# Faznet — notes for Claude Code

Faznet is Diar's free IPTV player for Android TV and phones (Smart IPTV style). It plays the user's own
M3U / Xtream playlists and **does not provide any channels** (keep it that way: no channel selling, no
built-in lists). Talk to Diar in **Albanian**; code, comments and commit messages in English.

## Build
- AGP 9.5 (built-in Kotlin), Compose plugin 2.4.21, Compose BOM 2026.09, KSP, Hilt, Room, Paging, compileSdk 37, minSdk 23,
  targetSdk 35. JDK 21: `JAVA_HOME=~/.jdks/jbr-21.0.11`.
- `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`). With `release.flag` in the project root the
  debug variant is a real release: R8 + shrink + release signing (key in `keystore/`, never committed).
- ABI splits → `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` (the **TV** APK: Mi TV Stick is 32-bit)
  and `app-arm64-v8a-debug.apk` (phones). No universal APK.
- `./gradlew testDebugUnitTest`: parser, import, repository and channel-list tests, no device needed (Robolectric).
- `./gradlew lintDebug` before every release: an error stops it, and is usually a crash on some device (minSdk 23).
- Every release: bump `versionCode` / `versionName` in `app/build.gradle.kts` (4.0.0 = code 40) and keep R8's `mapping.txt`
  as `archive/<version>/mapping.txt` (not in git), or crash reports can't be read.
- This folder is the git repository. `main` is what users run; `v5` is the restructure (plan and measurements in
  `ROADMAP.md`), and it stays local until Diar agrees to publish.

## Test device
Xiaomi Mi TV Stick (MiTV-AYFR0), Android 11, armeabi-v7a, 384 MB heap, Amlogic decoder. Use adb over Wi-Fi
(`adb connect <tv-ip>:5555`), `adb install -r`, `adb logcat | grep -i -E "faznet|exoplayer|mediacodec"`.
Known provider behaviour: Full HD channels often send a bad first second (picture frozen at start, fixed by
re-opening); HTTP 403 when zapping fast (account connection limit).

## Code map (app/src/main/java/com/faznet/tv)
- `ui/PlayerActivity.kt` — live player. Fresh player per channel, clean start (hidden until the picture runs),
  watchdogs in `player/PlaybackWatch.kt`: frozen picture, stuck playback, stall, 403 retries, last-resort re-open
  (15→30→60→120 s, counts frames). The channel list reads pages (`data/ChannelPages.kt`).
- `player/PlayerFactory.kt` (ExoPlayer/Media3 1.9 + Jellyfin FFmpeg audio). VLC was removed: it almost never helped and was 80% of the APK.
- `ui/MainActivity.kt` (thin host), `ui/HomeViewModel.kt`, `ui/compose/HomeScreen.kt` — Compose home (groups, channel grid
  from Paging, top bar, update button). `ui/compose/Theme.kt` — colours, `FocusBox` (remote + touch), `PivotScroll`
  (tvOS-style scrolling). `ui/compose/Logo.kt` — logos drawn straight from Coil, without composition.
- `ui/SettingsActivity.kt` (Compose settings), `ui/NetTestActivity.kt` (internet test), `ui/UpdateUi.kt` +
  `data/Updater.kt` (in-app updates via PackageInstaller, falls back to the system installer).
- `data/db/` — Room: channels, groups, favourites, history, state per playlist. `ChannelImporter` reads a list in as a
  stream; a new import replaces the old list only when it finished.
- `data/ChannelRepository.kt` — every read of a channel list, favourites, history, bringing lists up to date, and taking
  over what versions before the database saved. `data/ChannelScope.kt` — the kinds of list and their paging source.
- `data/Repository.kt` (prefs, playlists, stable MAC/PIN from ANDROID_ID), `data/M3uParser.kt` (streaming byte parser),
  `data/Xtream.kt` (VOD/series/EPG/catch-up), `data/Extras.kt` (ChannelMemory, ProblemLog, Parental),
  `data/Reports.kt` (problem/crash reports to the server), `data/RemoteSync.kt`. `di/AppModule.kt` — Hilt.
- `tools/` + `app/src/bench` — measuring on the stick, see `tools/README.md`.
- "Open last channel on start" is off by default.

## Website, updates, server
- `docs/` = GitHub Pages (https://whatsthatbrother.github.io/faznet/): landing page (EN default + SQ switch),
  playlist upload with MAC + PIN (do not change its behaviour), PayPal donate (paypal.me/EgzonFazliu).
- Publishing a version: copy the two APKs to `docs/download/Faznet-TV.apk` and `docs/download/Faznet-arm64.apk`,
  update `docs/version.json` (`version`, `code`, `notes` + `notes_en` in English, `notes_sq` in Albanian) and the
  version badge and the download sizes in `docs/index.html` (`<b>x.y.z</b>`, `~N MB`, EN + SQ), add the version to `CHANGELOG.md`, commit, push to `main`, tag `vx.y.z`, then make a GitHub Release from that tag with both APKs
  and the version's `CHANGELOG.md` entry as notes. Diar's rule (2026-11-06): GitHub shows a changelog only, in English —
  what is new and what was fixed, never how it was fixed or tested — and only the current release; older releases and
  their tags are deleted (backups of the old ones in `archive/github-releases-backup/`, not in git). The in-app notes in
  `version.json` stay in English + Albanian.
  The app checks `version.json` on every start (30 min throttle) and offers "Update now".
- `worker/` = Cloudflare Worker + KV (faznet.diarfazliu.workers.dev): device register, playlists, `/api/report`,
  `/admin/reports` (needs the secret key — see CLAUDE.local.md, never commit it). Deploy = paste into the
  Cloudflare dashboard editor.

## Working style
- Small, tested steps: build after every change; test on the Mi Stick before publishing.
- Keep the stated legal line ("Faznet does not provide any content"), no playlist URLs shown in the app.
