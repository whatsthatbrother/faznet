# Faznet — next release (1.1.4)

Agreed with Diar on 2026-10-22. All items are in 1.1.4 (EPG: Xtream channels only).

## Stability
1. [x] Fresh player + video decoder for every channel (fixes sound with frozen picture on Mi TV Stick)
2. [x] Faster auto-fix: re-open after ~2.5 s of frozen/no picture (was 4 s)
3. [x] HTTP 403/4xx: one retry after 2 s, then a clear "channel refused by the server" message
4. [x] Remove the Tunneling option (made it worse on the Mi Stick)
5. [x] Network comes back (Wi-Fi drop) → re-open the channel immediately

## Speed
6. [x] Fast zapping: only open the channel you stop on (debounce Up/Down, release the old connection at once)
7. [x] Open the last channel on start by default
8. [x] Cache the playlist on the device, open instantly, refresh from the server in the background
9. [x] Cache channel logos on the device

## Other
10. [x] EPG: now/next in the infobar and in the in-player channel list
11. [x] Show the PIN only after pressing a button
12. [x] Channel check: test the channels of a group in the background and mark dead ones (403/offline)

# 1.3: VLC engine done. Next: EPG grid (XMLTV), Catch-up, VOD & series, Compose for TV UI (2.0)

# Next (1.2) — agreed 2026-10-22, to do in the next session

## Credit
- [x] "Made by d1@r.f" in Settings → About, on the welcome screen and in the website footer

## Stability
- [x] Mi Stick H.264 start freeze: when a channel needed an auto-fix, remember it and open it the second way right away
- [x] Optional software video decoder for SD channels (576i) where the hardware decoder stalls
- [x] Watchdog log: last 50 auto-fixes/errors kept on the device, viewable in Settings (easier support)
- [ ] Remote crash reports to the Faznet server (opt-in), no playlist data

## Speed
- [x] Pre-connect to the next/previous channel's server while watching (faster zapping)
- [x] Remember the stream type per channel across restarts (skip the probe)
- [ ] Faster large-playlist parsing (stream parse, show groups while loading)

## Features
- [ ] Full EPG (XMLTV) for non-Xtream playlists, guide grid
- [ ] Catch-up / archive for Xtream channels that support it
- [ ] VOD & series (Xtream movies/series)
- [x] Parental PIN for chosen groups
- [ ] Multiple profiles / playlists switch from the player
- [x] Audio & subtitle track picker in the options bar
- [x] Sleep timer
- [x] Update notice inside the app when a new release is on GitHub
