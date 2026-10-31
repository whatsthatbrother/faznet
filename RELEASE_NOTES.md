## Faznet v4.0.2

**Shqip:** Testi i internetit nuk thotë më "Unstable" kur serveri IPTV punon mirë. Refresh pa internet e mban listën e kanaleve. Një rresht i prishur nuk e ndal më gjithë playlist-ën. Filmat dhe serialet ngarkojnë vetëm kategorinë ku ndalesh, dhe përditësimi i shkarkuar përgjysmë vërehet e shkarkohet sërish.

### Fixes
- Internet test: delay, jitter and loss are measured with plain TCP connections to the IPTV server. Many panels drop HTTP requests to their root page, so a healthy server showed 100% loss and "Unstable" (on the Mi TV Stick: now 43 ms, 3 ms jitter, 0% loss)
- Refresh with no internet or a server that is down keeps the copy on the device and shows the error, instead of emptying the home screen
- A stray "%" in Kodi-style options (`url|User-Agent=...`) no longer makes the whole playlist fail to load
- Movies / series: moving over the categories loads only the one the remote stops on (350 ms), and leaving a category cancels its download instead of letting it finish in the background
- Updates: a downloaded APK is used only if Android can read it and it is the expected version; a download cut off half way is fetched again instead of failing to install on every start
- Playlists removed on the website no longer leave their parsed copy, favourites and recent channels behind
- First unit tests (playlist parser)

## Faznet v4.0.1

**Shqip:** Ekran kryesor i ri me logo të mëdha të kanaleve (si Apple TV) dhe emra të plotë. Lëvizja me telekomandë është rreth dy herë më e butë në TV stick. Fotoja e ngrirë në VLC tani vërehet edhe kur zëri vazhdon, dhe kanali që mbaron vazhdimisht nuk provohet pafund.

### Home screen
- New tiles look: a big logo on a dark tile with the channel name under it; the focused tile grows slightly
- Full channel names are easier to read (the old three-column cards cut them short)
- Smoother on TV sticks (measured on the Mi TV Stick): janky frames down from 15–21% to about 8%, worst stall from 150 ms to 65 ms
  - no shadow under the focused item (it cost the stick's GPU more than the rest of the card)
  - the focus zoom redraws only its layer, not the card's content, on every frame
  - the window no longer paints a second full-screen background under the screen

### Player
- VLC: the freeze watchdog counts pictures shown, not decoded + shown, so a picture stuck on screen while the sound plays is noticed
- A channel that keeps reaching its end is retried a few times with a growing pause (1–5 s), then shows an error and is logged in the problem log

## Faznet v4.0.0

**Shqip:** Kanali që ngrin rihapet vetë edhe kur zëri vazhdon dhe të gjitha rregullimet e tjera janë përdorur (pas 15 s, pastaj 30/60/120 s). Pamje më e butë si në iOS/tvOS: lista rrëshqet butë dhe kanali i zgjedhur qëndron afër mesit, karta e fokusuar ngrihet me hije dhe lëvizje "spring", kalim i shpejtë mes ekraneve.

- Last-resort watchdog now also covers a frozen picture while the sound keeps playing (counts new pictures, not only the clock)
- tvOS-style pivot scrolling (smooth, focused row stays near the upper third) on the home screen and Settings
- Spring focus animation + soft shadow under the focused item
- Short cross-fade between screens instead of the slow default slide
- Update notice on screen: an "Update · Update now" button on the home screen when a new version is ready (checked every start, at most every 30 min), release notes in English (Albanian on Albanian devices)

## Faznet v3.0.5

**Shqip:** Roja e fundit e kanalit: nëse figura dhe ora e kanalit qëndrojnë 15 sekonda pa lëvizur (për çfarëdo arsye — gabim, buffering pa fund, ose kur rregullimet e tjera janë përdorur të gjitha), kanali rihapet vetë: pas 15 s, pastaj 30, 60, 120 s, derisa të punojë prapë.

- Last-resort watchdog for ExoPlayer and VLC: no progress (position/time and frames) for 15 s → re-open, backing off to 30/60/120 s; also leaves the error screen by itself when the channel comes back

## Faznet v3.0.4

**Shqip:** Kanalet hapen prapë shpejt: rihapja paraprake bëhet vetëm për kanalet që kanë ngrirë të paktën 2 herë dhe zbret me kohën (jo më për çdo kanal FHD). Logot dhe kartat e kanaleve më të vogla, ngjyra e fokusit ndërrohet menjëherë.

- Pre-reopen only for channels with 2+ start freezes, decays on every use; no more "likely FHD" guess (made opening slower)
- Smaller channel logos and cards, more columns on TV
- Instant focus colour, shorter focus zoom

## Faznet v3.0.3

**Shqip:** Testi i internetit te Settings (vonesa, jitter, humbje, shpejtësi dhe sinjali Wi-Fi, me vlerësim Stabil / I përdorshëm / Jo stabil); kartat e kanaleve më të vogla; gabimet e rrjetit nuk e ndërrojnë më motorin e player-it; motori rezervë mbahet mend vetëm kur kanali luan vërtet; 403 provohet dy herë; përditësimi kalon te instaluesi i Android kur telefoni e refuzon instalimin e qetë.

- Settings → Internet test: delay/jitter/loss to the user's IPTV server, download speed, Wi-Fi signal, verdict with reasons
- Smaller channel cards (more channels on screen), smaller groups
- ExoPlayer: network errors no longer switch to VLC; engine fallback saved only once the other engine really plays; old saved engine choices cleared once
- HTTP 403 (provider connection limit while zapping): two retries (2 s, 4 s)
- Updater: if the quiet install is refused (e.g. INSTALL_FAILED_VERIFICATION_FAILURE), Android's installer screen opens instead

## Faznet v3.0.2

**Shqip:** Butonat dallohen qartë: blu të mbushur, dhe të bardhë kur je mbi to me telekomandë (edhe në dritaret e dialogut); butonat e ikonave kanë rreth të dukshëm. Kanalet Full HD që ngrijnë në fillim rihapen menjëherë; ndërrimi i kanaleve me lart/poshtë është më i shpejtë.

- Clearly visible buttons: filled blue, white with dark text when focused (Views, dialogs, Compose); icon buttons have a visible circle
- Clickable cards have a thin frame; the focused one a white frame
- Channels that froze at start once (or Full HD channels after several such freezes this session) are re-opened right away
- Zapping delay 350 ms (was 450 ms)

## Faznet v3.0.1

**Shqip:** Përditësime nga vetë aplikacioni: versioni i ri shkarkohet në sfond nga faqja dhe instalohet me "Përditëso tani" (TV dhe telefon), pa humbur playlistat. Settings → Përditësimet. Kanalet dhe grupet lëvizin më shpejt.

- In-app updates from the website (version.json + APK per device type), installed in place with Android's package installer
- Settings: automatic updates on/off, "Check for updates"
- Faster home grid: cards only redraw when their own data changes; logos without fade-in

## Faznet v3.0.0

**Shqip:** Settings i ri (ekran i plotë, ndryshimet ruhen menjëherë), grupet ndërrohen më shpejt, ExoPlayer motori kryesor (VLC vetëm si rezervë për kanalet që nuk punojnë), "Hap kanalin e fundit" i fikur, problemet dërgohen vetë te zhvilluesi pa pyetur, dhe pas çinstalimit/instalimit pajisja e mban të njëjtin MAC + PIN (playlistat kthehen vetë nga faqja).

- New full-screen Settings made for the remote; every change is saved at once
- Faster group switching (channels grouped once; the remote passing over groups no longer rebuilds every list)
- ExoPlayer is the default engine; VLC stays as an option and as automatic per-channel fallback
- "Open last channel on start" is off by default
- Problems are reported to the developer automatically, without asking (bursts sent together, at most every 10 min)
- Stable device identity: the same MAC + PIN after a clean reinstall, so website playlists come back by themselves
- 2.1.1: VLC start watchdog

## Faznet v2.1.0

**Shqip:** Pamje blu-vjollcë në stilin e Smart IPTV; VLC tani rihap vetë kanalin kur figura ngrin ose ndalet, provon decoder-in software kur figura mbetet e zezë, dhe kalon te ExoPlayer kur një kanal nuk punon me VLC (dhe anasjelltas). Ndërrim më i shpejtë i kanaleve me VLC, playlistat hapen më shpejt pas rinisjes, ditari i problemeve dërgohet vetë një herë në ditë.

- Blue-violet look in the style of Smart IPTV (home, dialogs, player overlays)
- VLC freeze watchdog: re-open on frozen picture / stuck playback; software decoding for channels that stay black
- Per-channel engine fallback: a channel that keeps failing with one engine opens with the other one, and is remembered
- One shared LibVLC instance (faster zapping), prepared in the background at app start
- Parsed playlists cached in a binary file (faster start after a restart)
- Problem log sent automatically once a day when there are new entries (if automatic reports are on)

## Faznet v2.0.0

**Shqip:** Ekrani kryesor i ri, i bërë me Jetpack Compose për telekomandë dhe prekje: karta më të mëdha me logo, kanali i fokusuar zmadhohet me kornizë portokalli, kërkimi hapet në dritare të vogël, fokusi kthehet te kanali i fundit. Përfshin gjithçka nga 1.4.0 (filma, seriale, udhërrëfyes me catch-up, raportet e problemeve).

- New home screen built with Jetpack Compose (TV remote + touch): bigger channel cards, focus zoom and frame, groups open while moving over them
- Search in a small dialog (no keyboard popping up while moving with the remote)
- Focus starts on the last watched channel; returning from the player focuses the channel that was playing
- Includes 1.4.0: movies & series, TV guide with catch-up, resume, problem reports to the developer, crash reports, playlist switch in the player

## Faznet v1.4.0

**Shqip:** Filma dhe seriale (Xtream), udhërrëfyesi TV me catch-up, vazhdo aty ku e le, dërgimi i problemeve te zhvilluesi, ndrrimi i playlistës nga player-i, VLC si motor kryesor në TV.

## Faznet v1.3.0

**Shqip:** Motori i dytë VLC (Settings ose opsionet në player); dy APK: "TV" (~25 MB, për Mi TV Stick dhe TV stick të tjerë) dhe "arm64" (~28 MB, për pajisje të reja 64-bit). Edhe 1.2.2: hapje më e shpejtë e kanaleve dhe nisje më e shpejtë e aplikacionit.

- VLC (libVLC 3.7.6) as a second player engine: Settings → VLC engine, or long OK → engine button to switch while watching
- Clean start, sound/subtitle picker and picture size also work with VLC
- Per-ABI APKs: armeabi-v7a (TV sticks) and arm64-v8a; native libraries compressed
- 1.2.2: hidden re-open after 1 s, slow-start channels re-opened right away, baseline profile (faster app start)

## Faznet v1.2.1

**Shqip:** Nisje e pastër: kanali mbetet i fshehur pas rrethit të ngarkimit derisa figura të ecë normalisht — pa figurë të ngrirë kur ndërron kanal.

- Clean start: picture and sound stay hidden (loading circle) until the channel's picture runs; a freeze at start is re-opened quietly
- Start freezes (provider side) no longer switch the channel to the software decoder
- Software decoder only for real SD (576 lines or less)

## Faznet v1.2.0

**Shqip:** Made by d1@r.f. Kanalet SD që ngrinin hapen me decoder software; ditari i problemeve; zgjedhja e zërit dhe titrave; kohëmatësi i gjumit; njoftim për version të ri; kyçja prindërore.

- Credit "Made by d1@r.f" (Settings, welcome screen, website)
- Channels that needed an auto-fix are remembered; SD ones then open with a software video decoder
- Settings: software video decoder for all SD channels (optional)
- Problem log (last 50 auto-fixes / refused channels / errors) in Settings
- Stream type remembered across restarts (no second probe); neighbouring channels' servers resolved in advance
- Options (long OK): sound language & subtitles, sleep timer (30/60/90/120 min)
- Update notice when a newer release is on GitHub (checked once a day)
- Parental lock: 4-digit PIN for chosen groups, unlocked until the app is closed

## Faznet v1.1.4

**Shqip:** Player i ri për çdo kanal (pa figurë të ngrirë), ndërrim i shpejtë i kanaleve, rilidhje kur kthehet interneti, mesazh i qartë për kanalet e refuzuara (403), programi TV (Tani / Më pas) për kanalet Xtream, PIN-i i fshehur, kontrolli i kanaleve.

- Fresh player and video decoder for every channel; frozen picture is re-opened after ~2.5 s
- Fast zapping: only the channel you stop on is opened; the old stream is released at once
- Network comes back → the channel re-opens immediately
- HTTP 4xx (e.g. 403): one retry, then "channel refused by the server"
- EPG now/next in the infobar and the in-player channel list (Xtream Codes channels)
- Last channel opens on start by default; playlist opens from the device copy and refreshes in the background
- Channel logos cached on the device (80 MB)
- PIN is hidden until "Show PIN" is pressed (30 s)
- Long-press a group → check its channels; dead ones are dimmed
- Tunneling option removed

## Faznet v1.1.3

**Shqip:** Kanali hapet vetëm në kuadër të pastër (pa figurë të ngrirë kur ndërron kanal); opsioni Tunneling për TV stick; adresa e faqes së ngarkimit nuk shfaqet më në aplikacion.

- Video starts on a real IDR key frame; channels that never send one switch to tolerant mode automatically
- Settings: Tunneling (Android TV) for smoother playback on Amlogic sticks
- The upload website address, QR and server button are no longer shown in the app (MAC + PIN only)

## Faznet v1.1.2

**Shqip:** Figura që ngrin ndërsa zëri vazhdon rregullohet vetë; lista e kanaleve me OK mbi figurë (majtas/djathtas ndërron grupin); madhësia e figurës (Përshtat / Shtrij / Zmadho); lidhjet e playlist-ave nuk shfaqen më.

- Picture freeze watchdog: no first frame, frozen picture or stuck playback → the channel re-opens automatically
- Player buffer sized from device memory (lighter on 1 GB sticks such as the Mi TV Stick), faster channel start
- OK while watching opens the channel list of the current group over the picture; Left/Right switch group
- Infobar with logo and clock on every channel change; Menu opens options (list, favorite, picture size, stats)
- Picture size: Fit / Stretch / Zoom (remembered)
- TV safe area (overscan) and denser channel grid on TVs
- Playlist addresses, hosts and Xtream usernames are never shown in the app or on the website

## Faznet v1.1.1

**Shqip:** Ngarkimi i listës nga webi tani punon online: hap **https://whatsthatbrother.github.io/faznet**, shkruaj MAC + PIN që shfaq aplikacioni dhe shto listën. Pajisja e merr vetë.

- Online upload server (Cloudflare Worker) at `faznet.diarfazliu.workers.dev`
- Upload website on GitHub Pages: https://whatsthatbrother.github.io/faznet
- Signed release build (uninstall older test builds before installing)

## Faznet v1.1.0

**Shqip:** Stabilitet shumë më i mirë për kanalet IPTV, zë për kanalet MP2/AC3, ngarkim i playlist-ës nga webi me MAC + PIN, dhe përmirësime për Android TV.

### Playback
- Fixed periodic freezes on IPTV MPEG-TS streams whose audio and video are muxed seconds apart (buffer now prioritises time over size)
- Xtream Codes live URLs open directly as MPEG-TS (no extra probe connection, so single-connection accounts are not kicked)
- Extension-less URLs are detected (HLS / DASH / TS) before playback — opens on the first try
- Stall watchdog: a stream stuck buffering for 8 s reconnects automatically at the live edge
- No more speed-up to chase the live edge (it was eating the buffer)
- OkHttp networking with connection reuse — faster zapping
- FFmpeg software audio decoder (MP2, AC3, E-AC3, DTS…) — fixes channels without sound
- Stream stats overlay (Settings or Menu key): buffer, speed, codecs, decoder, stalls

### Upload from the web (MAC + PIN)
- Each device shows a MAC-style ID, a 4-digit PIN and a QR code
- Add M3U URLs, Xtream accounts or upload a file on the website; the device picks it up automatically
- Self-hosted server included (`server/`)

### TV & design
- D-pad over a category opens it; focused channel cards zoom; clock in the top bar
- Back from the player returns focus to the channel you were watching
- Requires Android 6.0+
