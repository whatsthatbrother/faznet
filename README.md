<p align="center"><img src="docs/icon-512.png" width="120" alt="Faznet"></p>
<h1 align="center">Faznet</h1>
<p align="center">Free IPTV player for Android & Android TV</p>

Faznet plays your own M3U / Xtream Codes playlists. It is free, has no ads and needs no activation.
Faznet does not host or provide any content.

## Download
Grab the latest APK from **[Releases](../../releases/latest)**.

## Features
- M3U URL, Xtream Codes and local-file playlists
- Categories, search, favorites, recently watched, logos
- Media3/ExoPlayer: HLS, TS, DASH; auto-reconnect
- Remote-friendly: ↑/↓ and CH± zapping, number entry, D-pad UI, Android TV launcher
- Swipe up/down to change channel on phones
- English / Shqip
- Upload playlists from the web with the device MAC + PIN (self-hosted server in `server/`)
- FFmpeg audio (MP2/AC3/E-AC3), stall watchdog, stats overlay

## Upload a playlist from the web
Open **https://whatsthatbrother.github.io/faznet**, enter the MAC + PIN shown in Faznet (device icon) and add an
M3U URL, Xtream account or file. The API runs on a Cloudflare Worker (`worker/`); a self-hosted Flask
alternative is in `server/`.

## Build
CI builds a debug APK on every push. Signed releases are built locally — the signing key is not in this repo
(`keystore/faznet.jks` + `keystore/keystore.properties`, see `app/build.gradle.kts`).

Local build: Android Studio (Ladybug or newer) → open the folder → Build ▸ Build APK(s).
