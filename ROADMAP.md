# 5.0: restructure (agreed with Diar on 2026-11-01)

Work happens on branch `v5`; `main` is what users run. Nothing is published without Diar's OK. Phases 0-2 went out as 4.1.0. 4.1.1 added the first step of Phase 3 (the freeze checks) and the later reveal. 4.1.2 added the paged player list and fitted phones. 4.2.0 removed VLC. 4.2.1 fixed the first nine findings of the stability audit.

1. [x] Phase 0: real git working copy, 4.0.3 source on GitHub, timing logs, measuring tools (`tools/`)
2. [x] Phase 1: quick wins without restructuring (library upgrades, byte M3U parser, channel opening, freezes, Home)
3. [x] Phase 2: data layer (Hilt, Room, streaming import, migration of favourites and recents; playlists, MAC + PIN stay in preferences)
4. [ ] Phase 3: player split in pieces with tests (engines, watchdog, ViewModel, Compose overlay)
5. [ ] Phase 4: redesign, one design system, every screen in Compose (2-3 looks to choose from on the TV)
6. [ ] Phase 5: baseline profile from the emulator, phones and tablets, clean-up, 5.0.0

Targets with 50 000 channels on the Mi TV Stick: under 5% late frames, 99% of frames under 35 ms, restart to list under
1 s, channel switch around 1.5 s. Decide about keeping VLC in Phase 3 (it is 80% of the APK; in the reports ExoPlayer
fell back to it once).

## Freezes at the start: what is known (2026-11-01)

Comparison on the stick, channels 4-103, four rounds of 100 switches, 22:41-23:18 (reports from the stick in that time are
this test, not use). Each round about 80 channels opened clean in 1.03-1.09 s (median).

| | Clean | Froze once, then fine | Never ran, shown after 9 s |
|---|---|---|---|
| Video view transparent while opening (as published) | 82-83 | 19-21 | 14-15 |
| Black cover over a visible video surface | 83 | 23 | 14-15 |

- Hiding the picture is **not** the cause: the cover did not help, so it was taken out again.
- Frozen channels stop at 5 pictures in 88 of 98 logged cases (also 4, 6, 9), and usually after the picture appeared, since
  it appears at 5. So the viewer sees it start, stop, and start again.
- It reproduces at about 20% in this range of channels, so a fix can be measured. Earlier runs (0 of 20, 0 of 95) came from
  other conditions not yet understood.
- The 14-15 that never ran were the same number every round and never reached READY. Probably dead or refused channels, not
  confirmed: `zap-test` output should keep the channel names to tell. They keep the viewer behind the circle for 9 s.

Later the same night (until 23:58, test builds on the stick, so reports up to then are tests too), channels 4-43 with the full
log, per opening:

| | First opening froze | Next opening froze |
|---|---|---|
| Progressive (the decoder log has no "is interlaced", 40 ms between pictures) | 0 of 19 | |
| Interlaced (20 ms between pictures, usually MP2 sound) | 17 of 21 | 0 of 17 |

So it is the Amlogic decoder with interlaced H.264. Its log says `dec_dpb_size=5`, and it stops at about 5 pictures (the
reason for that number is a guess). It goes silent about 100 ms before.

No effect, 12-13 freezes in each round of 18 channels: no info panel, not stopping the old player while zapping, releasing it
at once, no hiding at all, no frame listener. Also no effect: re-opening at the first picture or when the decoder starts (so a
short opening does not cure the next), and a throwaway `MediaCodec` before each opening. "Every second decoder" looked true
for one run and failed with an exact count. Why the opening after a freeze is clean is **not known**.

Done about it: the picture now appears at 10 frames, not 5. In a round of 18 channels, 12 froze and the viewer saw none of them
start and stop. It costs clean channels about 0.2 s (1.25-1.3 s to picture); interlaced channels that freeze show after 2.8-3.2 s.
Not tried yet: tunneling on interlaced channels only, the software decoder on them, vendor decoder parameters.

On 2026-11-02 at about 20:28 the same channels showed **0 re-opens in 20** (11 and 14-17 among them) with nothing changed that
touches the decoder, as at 15:41 the day before; at 22:41-23:58 and in the evening reports they froze at about 80%. So it does
come in waves, also on interlaced channels, and something outside the app switches it. A fix can only be judged while it is on.

Redmi Note 7 (Android 10, 174 838 channels), 2026-11-02: no freeze in about 15 openings; too few to say it never happens there.
With pages the player reads its list in 0 ms instead of 2.2-4.1 s and holds 10-16 MB of Java heap instead of 70-86 MB.
Stick, same day, 20 switches: median 1.5 s, worst 2.4 s (hiding until 10 pictures); list, groups, number entry, guide fine.

## Baseline: 4.0.3 on the Mi TV Stick, 2026-11-01

Real playlist (5978 channels), Compose 1.7:

| | Result |
|---|---|
| Cold start to first frame | 740-1200 ms; list loaded 550-870 ms after process start |
| Remote, one press at a time | 4% late frames, p99 21 ms |
| Remote held down | 50% late frames, p99 500-600 ms |
| Moving over the groups | 12-15% late frames, p95 65 ms, p99 90-105 ms |
| Channel switch, press to picture | median 1.76-2.05 s (0.35 s wait, 0.8 s to first frame, 0.65 s hidden until "stable") |
| Channels that re-opened themselves | 1 of 15, 8 of 20 (p90 5.7 s, worst 9.8 s), 0 of 95 with the trace build: comes in waves |
| Memory (PSS) | 54-96 MB |

Synthetic 50 000 channels (`tools/bench.ps1`), Compose 1.7:

| | Result |
|---|---|
| First load (regular-expression parser) | 9.2 s |
| Load from the device copy | 0.64 s, plus 105-130 ms and 86-168 ms on the main thread |
| Player `buildGroups` on the main thread | 127-155 ms per channel opened |
| Search, per letter on the main thread | about 190 ms |
| Remote one press at a time | 2-3% late frames, p99 22 ms |
| Remote held down | 36-86% late frames, p99 1.5-4.7 s |

What a real freeze looks like (KLAN HEVC, trace build): after 5 pictures the video decoder takes no more input and gives no
more output (`vIn=87 vOut=5` for 8 s) while sound and clock run on. A new decoder fixes it. Providers send 25-30 s at
once when a channel opens, and the first picture comes within 0.2 s of the first timestamp, so the start itself is clean.
Reports (8 days, 5 devices): no crashes; 38 of 80 problem lines are "picture frozen at start", all on the Mi Stick.

## After phases 1 and 2, same stick, 2026-11-01

Real playlist (5978 channels), Compose 1.12. The Compose update shortened the scroll animation, so a late-frame percentage
is of fewer frames than before: compare the milliseconds.

| | 4.0.3 | Now |
|---|---|---|
| Cold start to first frame | 740-1200 ms | 617 ms |
| List on screen | 550-870 ms | 346-363 ms |
| Remote, one press at a time | 4% late frames, p99 21 ms | 11-12% late frames, p99 28-31 ms: **target of 5% not met** |
| Keys as fast as adb sends them | p99 500-600 ms | p90 27-29 ms, p99 46-48 ms |
| Moving over the groups | p95 65 ms, p99 90-105 ms | p95 31-34 ms, p99 57-69 ms |
| Channel switch, press to picture (20 channels) | median 1.76-2.05 s, p90 up to 5.7 s | median 1.21 s, p90 1.35 s, worst 1.44 s |
| Channels that re-opened themselves | 1 of 15, 8 of 20, 0 of 95 | 0 of 20; it comes in waves, so one run proves little |
| Memory (PSS) after start, after use | 54-96 MB | 44-45 MB, 60-78 MB |

Synthetic 50 000 channels, measured before the last focus fix (a state read while drawing, nothing else on that path):

| | 4.0.3 | Now |
|---|---|---|
| First load | 9.2 s | 5.9 s (the parser is 1 s of it; the rest is writing to the database) |
| Load from the device | 0.64 s, main thread stalls of 100-190 ms | 340-516 ms |
| Key really held down | 36-86% late frames, p99 1.5-4.7 s | 1.1% late frames, p99 17 ms |
| Moving over the groups | not measured | p95 32 ms, p99 61 ms |
| One press at a time | 2-3% late frames, p99 22 ms | 7-9% late frames, p99 26-31 ms |
| Memory (PSS), Java heap | not measured | 46-64 MB, about 6 MB |

Checked by hand on the stick: focus look, coming back from the player, favourites from Home and the player, search, the
player's channel list, number entry, TV guide, Device and Settings screens, playlist switch, parental lock with a wrong and a
right PIN. After using all of those: 1 Activity and 1 ViewRootImpl left, no crash in the log. VLC plays too (2.2-2.7 s to
picture, 88 MB). Phone and TV emulators: the same screens, portrait and landscape, and a full in-app update. Not checked:
playback through a weak network, a real phone.

Known for later: the TV guide opens with the focus on Back at channel 1, and Search keeps the group highlighted. The player
still reads a whole group into memory (Phase 3).

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
