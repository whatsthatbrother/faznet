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
