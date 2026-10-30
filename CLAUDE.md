# Faznet — notes for Claude Code

Faznet is Diar's free IPTV player for Android TV and phones (Smart IPTV style). It plays the user's own
M3U / Xtream playlists and **does not provide any channels** (keep it that way: no channel selling, no
built-in lists). Talk to Diar in **Albanian**; code, comments and commit messages in English.

## Build
- Kotlin, AGP 8.13, Kotlin 2.1.21 + Compose plugin, compileSdk 36, minSdk 23, targetSdk 35.
- `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`). With `release.flag` in the project root the
  debug variant is a real release: R8 + shrink + release signing (key in `keystore/`, never committed).
- ABI splits → `app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk` (the **TV** APK: Mi TV Stick is 32-bit)
  and `app-arm64-v8a-debug.apk` (phones). No universal APK. VLC makes the APKs ~25/29 MB.
- Every release: bump `versionCode` / `versionName` in `app/build.gradle.kts` (4.0.0 = code 40).

## Test device
Xiaomi Mi TV Stick (MiTV-AYFR0), Android 11, armeabi-v7a, 384 MB heap, Amlogic decoder. Use adb over Wi-Fi
(`adb connect <tv-ip>:5555`), `adb install -r`, `adb logcat | grep -i -E "faznet|exoplayer|mediacodec|vlc"`.
Known provider behaviour: Full HD channels often send a bad first second (picture frozen at start, fixed by
re-opening); HTTP 403 when zapping fast (account connection limit).

## Code map (app/src/main/java/com/faznet/tv)
- `ui/PlayerActivity.kt` — live player. Fresh player per channel, clean start (hidden until the picture runs),
  watchdogs: early freeze (1 s), frozen picture, stuck playback, stall, 403 retries, per-channel engine fallback
  (ExoPlayer ↔ VLC, remembered only once it plays), last-resort `hardWatch` (15→30→60→120 s, counts frames).
- `player/PlayerFactory.kt` (ExoPlayer/Media3 1.9 + Jellyfin FFmpeg audio), `player/VlcEngine.kt` (shared LibVLC).
- `ui/MainActivity.kt` + `ui/compose/HomeScreen.kt` — Compose home (groups, channel grid, top bar, update button).
  `ui/compose/Theme.kt` — colours, `FocusBox` (remote + touch), `PivotScroll` (tvOS-style scrolling).
- `ui/SettingsActivity.kt` (Compose settings), `ui/NetTestActivity.kt` (internet test), `ui/UpdateUi.kt` +
  `data/Updater.kt` (in-app updates via PackageInstaller, falls back to the system installer).
- `data/Repository.kt` (prefs, playlists, binary parsed-list cache, stable MAC/PIN from ANDROID_ID),
  `data/M3uParser.kt`, `data/Xtream.kt` (VOD/series/EPG/catch-up), `data/Extras.kt` (ChannelMemory, ProblemLog,
  Parental, EnginePrefs), `data/Reports.kt` (problem/crash reports to the server), `data/RemoteSync.kt`.
- Default engine is **ExoPlayer** (VLC optional). "Open last channel on start" is off by default.

## Website, updates, server
- `docs/` = GitHub Pages (https://whatsthatbrother.github.io/faznet/): landing page (EN default + SQ switch),
  playlist upload with MAC + PIN (do not change its behaviour), PayPal donate (paypal.me/EgzonFazliu).
- Publishing a version: copy the two APKs to `docs/download/Faznet-TV.apk` and `docs/download/Faznet-arm64.apk`,
  update `docs/version.json` (`version`, `code`, `notes` + `notes_en` in English, `notes_sq` in Albanian) and the
  version badge in `docs/index.html` (`<b>x.y.z</b>`, EN + SQ), add `RELEASE_NOTES.md`, commit, push to `main`.
  The app checks `version.json` on every start (30 min throttle) and offers "Update now".
- `worker/` = Cloudflare Worker + KV (faznet.diarfazliu.workers.dev): device register, playlists, `/api/report`,
  `/admin/reports` (needs the secret key — see CLAUDE.local.md, never commit it). Deploy = paste into the
  Cloudflare dashboard editor.

## Working style
- Small, tested steps: build after every change; test on the Mi Stick before publishing.
- Keep the stated legal line ("Faznet does not provide any content"), no playlist URLs shown in the app.
