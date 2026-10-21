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
