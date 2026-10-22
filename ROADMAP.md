# Faznet — next release (1.1.4)

Agreed with Diar on 2026-10-22. All items are in 1.1.4 (EPG: Xtream channels only).

## Stability
1. [x] Fresh player + video decoder for every channel (fixes sound with frozen picture on Mi TV Stick)
2. [x] Faster auto-fix: re-open after ~2.5 s of frozen/no picture (was 4 s)
3. [x] HTTP 403/4xx: one retry after 2 s, then a clear "channel refused by the server" message
4. [x] Remove the Tunneling option (made it worse on the Mi Stick)
5. [ ] Network comes back (Wi-Fi drop) → re-open the channel immediately

## Speed
6. [ ] Fast zapping: only open the channel you stop on (debounce Up/Down, release the old connection at once)
7. [ ] Open the last channel on start by default
8. [ ] Cache the playlist on the device, open instantly, refresh from the server in the background
9. [ ] Cache channel logos on the device

## Other
10. [ ] EPG: now/next in the infobar and in the in-player channel list
11. [ ] Show the PIN only after pressing a button
12. [ ] Channel check: test the channels of a group in the background and mark dead ones (403/offline)
