# Measuring Faznet on the stick

These scripts are how every speed claim in the release notes is checked. Run them from the project root in PowerShell.

```powershell
$env:FAZNET_STICK = '<ip>:5555'     # stays out of the repository
adb connect $env:FAZNET_STICK
```

They refuse to touch the stick while another app is playing: someone may be watching.

| Script | What it measures |
|---|---|
| `ui-bench.ps1` | Home: cold start, frame times with the remote (one press at a time, held down, over the groups) |
| `zap-test.ps1` | Channel switching: press to picture, re-opens, memory and leaked screens after N switches |
| `bench.ps1` | The measuring app (`gradlew assembleDebug -Pbench`) with synthetic 10k/50k/100k lists |
| `ui.ps1` | List the texts on screen, tap by text, screenshot |

The bench app (`com.faznet.tv.bench`) sits next to the real one: same code and R8 settings, its own data, no launcher
icon, no server, no updates. Its output goes to `app/build-bench/`, so the release mapping in `app/build/` stays intact.

`gen_m3u.py` writes the synthetic lists. They hold no real channels, addresses or accounts.

Screenshots of the player come out white on the stick (the video plane), and panels hide after a few seconds. To see what
the player shows, read the texts with `uiautomator dump` right after the key press and the opened channel from the
`open ...` lines in the log.

## Trying the updater before a release

A broken updater would leave every user on that version, so it is tried with a site on this PC, on the emulators (the bench
app also builds for x86_64; the phone image's Play Protect stops the install, so finish on the TV image):

1. `gradlew assembleDebug -Pbench -PbenchVersion=43 -PfaznetWeb=http://127.0.0.1:8765` and install that APK.
2. The same with `-PbenchVersion=44`: its APK goes to `<folder>/download/Faznet-TV.apk`, next to a copy of
   `docs/version.json` with code 44.
3. `python -m http.server 8765 --bind 127.0.0.1 --directory <folder>` and `adb reverse tcp:8765 tcp:8765`, then open the app.

Start emulators with `-read-only -no-window`, so nothing stays on them and they don't get in the way.
