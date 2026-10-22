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
