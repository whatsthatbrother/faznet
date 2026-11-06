## Faznet v4.3.3

**Shqip:** Raportet e problemeve tash tregojnë edhe kur aplikacioni u mbyll nga një crash në kodin e sistemit (foto ose video), kur nuk u përgjigj, ose kur sistemi e mbylli për memorie ndërsa ishte në ekran. Këto më parë nuk arrinin fare. Vetëm Android 11 e më i ri, vetëm me raportet automatike të ndezura, pa adresa. Gjithçka e 4.3.2 mbetet. Playlist-at dhe favoritet mbeten.

### Why
- Mi TV Stick, 06.11 18:33, 20 s after the stick was restarted: the app closed on a native crash ("Scudo ERROR: corrupted chunk header") while Android decoded a channel logo. Something in native code had written over memory before; the log can't say what
- Problem reports only caught crashes in Kotlin/Java, so how often this happens to anyone was not known

### Reports
- At start, Android 11 and newer: how the app ended since the last look, from Android's own record
- Sent: a native crash, "not responding" with the main thread's stack (what held the screen), and being killed for memory while on the screen
- Not sent: leaving the app, an update, being killed in the background, a Kotlin/Java crash (that has its own report already)
- The first time, the last day is included
- On Android 11 a native crash only says "crash", without the library; Android 12 and newer keep more, not read yet

### Also found on 06.11
- The Mi TV Stick's "bad periods", where interlaced 1080 channels freeze after 5 pictures at every opening (ROADMAP.md), ended when the stick was restarted: channels that froze before opened in about 1.5 s with no re-opening. The decoder's state, not the channels

### How it was tested
- 101 automatic tests (6 new), Lint with no errors
- Mi TV Stick: the 18:33 crash reached the server as a report at the next start; a channel in 1.45 s; no crash

## Faznet v4.3.2

**Shqip:** Kanalet Full HD dhe HD mbeten përsëri te dekoderi i videos së TV-së. 4.3.1 disa prej tyre i kaloi te dekoderi software, të cilin Mi TV Stick nuk e lëviz rrjedhshëm në këtë madhësi (rreth 5 foto në sekondë, dhe rihapje çdo gjysmë minute). Dekoderi software tash përdoret vetëm për kanalet SD, dhe kthehet vetë nëse nuk ia del. Gjithë pjesa tjetër e 4.3.1 mbetet. Playlist-at dhe favoritet mbeten.

### What 4.3.1 got wrong
- It moved a channel to the software decoder after two freezes in a row, whatever its size. On the Mi TV Stick that decoder shows about 5 pictures a second of a 1080 channel and drops the rest; the player then fell into buffering and opened the channel again every 30-40 s
- KLAN KOSOVA HD was moved that way the same evening. It is one of the channels whose first openings freeze only at times, and at those times the hardware decoder also freezes every opening, so two freezes in a row did not tell it apart from KLAN PLUS FHD

### Now
- The software decoder is only chosen for pictures up to 576 lines (SD), by the freeze rule, the "software decoder for SD" setting, or earlier fixes
- Channels 4.3.1 moved to it at a bigger size go back to the hardware decoder by themselves
- If the software decoder drops more pictures than it shows (after about 100), the channel goes back to the hardware one at once and stays there

### Known, not fixed here
- Some interlaced 1080 channels (KLAN PLUS FHD, KLAN KOSOVA HD) freeze after 5 pictures at every opening on the Mi TV Stick while the decoder is in one of its bad periods, as in 4.3.0. Their HD or HEVC versions, where the list has them, play on the hardware decoder (KLAN PLUS HEVC: 1.4 s to picture, no dropped frames). Not tried yet: tunneling, vendor decoder settings
- The software decoder on SD channels has not been measured on the stick yet; if it can't keep up, the guard above sends the channel back

### How it was tested
- 95 automatic tests, Lint with no errors
- Mi TV Stick: KLAN KOSOVA HD, marked for software by 4.3.1, opens on the hardware decoder again; KLAN PLUS HEVC 1.4 s, no dropped frames; no crash

## Faznet v4.3.1

**Shqip:** Një kanal që e ndal dekoderin e videos së TV-së në çdo hapje (fotoja ngrin pas pak kornizash) tash kalon vetë te dekoderi software dhe e mban mend. Në Mi TV Stick një kanal Full HD atëherë lëviz, por jo rrjedhshëm: përdor versionin HD ose HEVC nëse lista e ka. Filmat, serialet dhe guida nuk e ngrijnë më ekranin kur ngarkohet një listë e madhe, guida e tregon programin menjëherë, dhe një film vazhdon vetë pas një ndërprerjeje të shkurtër të internetit. Playlist-at dhe favoritet mbeten.

### A channel that stops the hardware decoder every time
- Report of 06.11: KLAN PLUS FHD stops the Mi TV Stick's decoder at every opening (83 frames in, 5 pictures out), and re-opening never helped. The same channel plays on a PC from the same server
- Two freezes in a row on the same channel, with no healthy playing between them, now switch it to the software decoder, and the channel keeps that. One freeze followed by a channel that plays changes nothing (the interlaced channels in ROADMAP.md)
- If the software decoder can't start a channel, it goes back to the hardware decoder at once
- On the Mi TV Stick the software decoder had never really been used: its firmware does not mark `OMX.google.h264.decoder` as software, and ExoPlayer sorted the hardware decoder first again. That also concerns the "software decoder for SD" setting, which now takes effect
- The Stick's processor shows about 6 pictures a second of a 1080 channel in software (the rest are dropped). A moving picture instead of a frozen one, but not smooth. KLAN PLUS HEVC, also 1080, plays on the hardware decoder with no dropped frames

### Movies, series, TV guide
- Lists from the account are read off the main thread: "All" can be several MB, which held the screen still for seconds
- The guide shows the programme at once. Which channels keep an archive (the account's whole channel list) comes after it and is kept for 30 minutes; the server's time zone once per run
- A movie, an episode or catch-up continues by itself from the same place after a network error, up to 5 times
- Leaving the movie player gives its buffer memory back, as the live player does

### Also
- A sleep timer goes on after the app was in the background (TV switched off, Home) instead of being lost
- Upload server: guessing a device's PIN is limited (it was possible without a limit through registration), a few tries per address and minute, and problem reports per address

### How it was tested
- 95 automatic tests (6 new), Lint with no errors
- Mi TV Stick: KLAN PLUS FHD before (frozen at 5 pictures, 4 re-opens) and after (software decoder from the third opening, 3.5 s, no freezes, about 6 pictures a second); KLAN PLUS HEVC on the hardware decoder (1.3 s, no dropped frames); movies, series, guide, catch-up, a movie with Wi-Fi off for a few seconds, the sleep timer: no crash, no "not responding"
- Upload server: 23 tests against the code that runs on Cloudflare, normal use and the three ways of guessing a PIN

### Not tried
- A phone

## Faznet v4.3.0

**Shqip:** Kur serveri e refuzon një kanal, mesazhi del pas rreth 3 sekondash në vend të 16, me 5 kërkesa në vend të 12. Nëse refuzimi kalon, kanali fillon vetë dhe mesazhi zhduket. Raportet e problemeve tash thonë si përfundoi çdo ndërprerje dhe çka dinte player-i në atë çast. Playlist-at dhe favoritet mbeten.

### A channel the server refuses (HTTP 4xx)
- The message appears after about 3.5 s instead of about 16 s
- 5 requests instead of up to 12, on an account that may already be at its connection limit
- Two quiet tries still run behind the message, up to 15 s, and the channel starts by itself if the refusal passes
- Once a channel plays, nothing changes: a refused segment is retried by the player while the buffer covers it

### Problem reports
- A line now says how it ended: back after, not back after, or left after so many seconds
- It also says how long the picture, the clock and the decoder had stood still, what was in the buffer, when the network last sent anything, the audio, the format and the kind of load error (one word such as `http403` or `timeout`)
- A stream that ended, buffering for 8 s and a network error used to re-open without a line. Now they have one
- No addresses or error texts: numbers and the channel's name, as before

### How it was tested
- 89 automatic tests, Lint with no errors
- Mi TV Stick with real channels: 30 channel switches (median 1.35 s), a channel the server really refuses (message at 3.3 s, 5 requests, then it stops), a report reaching the server with no address in it
- TV emulator with a local server: a channel refused for good, one that starts after 4 refusals with the message gone, a stream that ends every few seconds. Sound only, without video

### Not tried
- A phone
- A real channel coming back after a refusal

### Known, not fixed here
- A channel can take about 9 s to open when the start check re-opens it several times. One report suggests some of these are false alarms; the new reports will show

## Faznet v4.2.1

**Shqip:** Version stabiliteti. Lista e kanaleve në player nuk mbyllet më kur lëviz poshtë, një përgjigje e gabuar e serverit nuk e zëvendëson më listën tënde, dhe një listë e re nuk e pengon më player-in. Rregullohen edhe testi i internetit, një crash në Android 6 dhe sjellja kur memoria e pajisjes është plot. Playlist-at dhe favoritet mbeten.

### Fixes
- Channel list over the picture: no more crash when scrolling past row 60 of a long list, for example the results of a search
- A new list can no longer lose channels: clean-up used to delete channels of an import still running, and two imports at once destroyed each other. Now one list changes at a time
- An empty answer or a web page from the server no longer replaces a good list. The list stays and a message says the server sent no channels
- The player and the guide keep the list they were opened with until you are back on Home
- Android 6: a call that does not exist there is gone. `gradlew lintDebug` now stops a build with such an error
- Internet test: it said "unstable" on fast connections, because the test file was finished too quickly to measure
- The address of a channel given as a playlist is refused at once instead of downloading without end. A playlist can be at most 500 MB
- A full device no longer closes the app while it saves, and a favourite that cannot be saved says so

### What you may notice
- An automatic refresh refuses a list under half the size of the one on the device. The refresh button accepts it, because then you asked for it
- An old list stays on the device until Home is in front again, so for a moment two lists take up space

### How it was tested
- 78 automatic tests, Lint with no errors
- Mi TV Stick with real channels: the search list down to row 105 of 500, 20 channel switches (median 1.4 s, none needed a re-open), internet test, a refresh
- Redmi Note 7 with 174 873 channels: the search list down to row 81 of 500, 30 channel switches (median 1.3 s), a refresh with the player open
- Mi TV Stick with the measuring app and 50 000 made-up channels: a new list arriving with the player open, an empty answer, a web page, the address of a channel
- No crash in any of these

### Not tried
- Importing a real list that changed: the real lists had not changed on the test days, so this ran only with made-up channels
- Android 6 on a real device, and a really full device (simulated in the tests)
- The player when its list has gone completely

### Known, not fixed here
- A channel the server refuses (HTTP 403) takes about 9 s or more before its message shows, because every attempt is retried

## Faznet v4.2.0

**Shqip:** Aplikacioni është rreth 6 herë më i vogël, 4 MB në vend të 25 MB. Motori VLC është hequr: kanalet i luan ExoPlayer, dhe cilësimi i VLC-së nuk është më. Playlist-at dhe favoritet mbeten.

### Changes
- APK of about 4 MB instead of 25 MB
- VLC removed, with its setting, the engine button in the player and the per-channel engine memory. A channel ExoPlayer cannot play now shows an error, with no second engine to try
- `#EXTVLCOPT` lines in playlists are still read

### Why
- In the problem reports VLC shows 15 lines, all from old versions, and one automatic switch to it, after a network error. That does not prove it never helped anyone

### How it was tested
- Mi TV Stick: 15 channel switches, list of options and the FFmpeg sound decoder, with the same code before the version number changed
- Not tried without VLC on a real phone with real channels

## Faznet v4.1.2

**Shqip:** Me playlist të mëdha, player-i hapet dhe ndërron kanale menjëherë, me shumë më pak memorie. Në telefon, shiriti i sipërm, filmat, serialet dhe TV Guide përshtaten me ekranin, dhe player-i hapet horizontalisht. Kanali që serveri e refuzon tani e thotë këtë dhe nuk provohet më pa fund.

### Big playlists (measured on a Redmi Note 7 with 174 838 channels)
- The player and the TV guide read channels a page at a time: 0 ms instead of 2.2-4.1 s before Up/Down worked, 10-16 MB of Java heap instead of 70-86 MB

### Phones
- Top bar in two rows: search shows its text, and Settings stays on the screen
- Movies and Series: categories in a row above full-width posters
- TV guide: channels above their programmes, opening at the channel last played
- "Upload from the web": the MAC on one line and buttons that fit
- Live and VOD players open in landscape

### Fixes
- A channel the server refuses (HTTP 4xx): after the retries the message stays on the screen and nothing tries again until the viewer moves on. It used to vanish and keep opening connections, which is what an account's connection limit counts. It can still take about 15 s to appear
- Channel names written for a web page ("&amp;") show as plain text

### How it was tested
- Redmi Note 7 (Android 10): all screens, with an earlier build of these changes; the refused-channel fix was not tried there
- Mi TV Stick: this build, with the refused-channel fix; channels, list, number entry and guide with the build just before it. Freezes on interlaced channels are not fixed, and were absent in that day's test

## Faznet v4.1.1

**Shqip:** Kanali nuk nis, ndalet dhe nis përsëri në TV stick. Kanalet interlaced qëndrojnë pas rrethit të ngarkimit derisa fotoja të luajë vërtet, rreth 3 s në stick-et ku dekoderi ngec me to; kanalet e tjera hapen rreth 0.2 s më ngadalë se në 4.1.0.

### Fix
- Mi TV Stick: the video decoder stopped on 17 of 21 first openings of interlaced channels (0 of 19 progressive), usually at 5 pictures, and the next opening played. The picture now appears after 10 pictures, so the viewer no longer sees the channel start, stop and start again. In a test with 18 channels, 12 froze and none showed it. The decoder's freeze itself is not fixed: those channels take 2.8-3.2 s to appear, others 1.25-1.3 s
- The opening log timed a later re-open from the first key press

### Under the hood
- The player's freeze checks moved out of the player screen, with 23 new unit tests (46 in all)

### How it was tested
- Mi TV Stick: this build with ExoPlayer and VLC, and the freeze numbers above from the same code. Not tried on a real phone

## Faznet v4.1.0

**Shqip:** Shumë më e shpejtë dhe më e lehtë. Kanalet hapen për rreth 1.2 s në vend të 2 s, një dekoder video i bllokuar vërehet dhe rregullohet më shpejt, dhe aplikacioni niset më shpejt. Listat e kanaleve tani ruhen në një databazë, kështu që playlist-at me dhjetëra mijëra kanale lëvizin butë. Playlist-at dhe favoritet mbeten.

### Faster (measured on the Mi TV Stick against 4.0.3)
- Channel switch, press to picture: about 1.2 s instead of 1.8-2.0 s, and 9 of 10 within 1.35 s (it could take 5.7 s)
- Start to first picture of the home screen: 617 ms instead of 740-1200 ms; the list is on screen in about 350 ms
- Moving through channels as fast as the remote allows: 99% of frames under 48 ms instead of 500-600 ms
- 50 000 channels: first load 5.9 s instead of 9.2 s, later loads under 0.6 s, a key held down stays smooth instead of stalling for seconds
- Memory right after start: 45 MB instead of 54-96 MB

### Steadier
- The player tells a stuck video decoder from a picture that is merely late, and opens the channel again sooner
- Problem reports say what the decoder was doing, so freezes can be tracked down
- Home: buttons no longer stay lit after the focus moved on, and removing the last favourite keeps the focus nearby instead of sending it to the top bar
- Parental lock: a list with locked groups no longer gives a channel in a locked group a place in the list
- Update notes appear in Albanian on Albanian devices
- Phones: the "Update" button has its own row, so search and Settings stay on the screen while an update waits

### Under the hood
- Channel lists, favourites and recently watched are in a database (Room) and read a page at a time (Paging); dependency injection with Hilt
- The M3U file is read as a stream of bytes, about 8 times faster
- Favourites and recently watched from older versions carry over on the first start; MAC + PIN do not change
- Current build tools and Compose; measuring tools in `tools/`, more unit tests (23)

### How it was tested
- Mi TV Stick: all screens and channel playback, with a build that differs from this one only by the phone button above, the update notes language and the version number
- Phone and TV emulators (Android 37 and 36): all screens, portrait and landscape, and a full in-app update; no real channel was played there
- Not tried on a real phone

## Faznet v4.0.3

**Shqip:** Kanali nuk mbetet më i ngrirë kur sinjali HDMI i TV-së ndërpritet për një çast (ndërrim input-i, standby, lidhje e lirshme). TV live tani vazhdon të luajë, dhe nëse sistemi e pauzon prapë, kanali rihapet vetë.

### Fix
- Live player: Android sends "audio becoming noisy" when the HDMI signal drops for a moment; ExoPlayer paused on it, and the watchdogs only watch a player that is meant to play, so the channel stayed frozen until the user left it (seen on the Mi TV Stick: HDMI gone for 0.6 s, channel paused for good). Live TV has no pause button, so it no longer pauses on that signal; any other pause by the system re-opens the channel at the live edge and is written to the problem log

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
