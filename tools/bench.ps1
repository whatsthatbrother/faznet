# The measuring app with big synthetic lists: build it once (gradlew assembleDebug -Pbench), then
#   tools\bench.ps1 -List 50000            makes that list active in the bench app, then use ui-bench.ps1 -Package com.faznet.tv.bench
#   tools\bench.ps1 -List 50000 -Data      times reading it without any screen (safe while the stick is in use)
#   tools\bench.ps1 -Remove                uninstalls the bench app
param([int]$List = 50000, [switch]$Data, [switch]$Remove, [int]$Rounds = 3, [string]$Apk)
. "$PSScriptRoot\common.ps1"
$pkg = 'com.faznet.tv.bench'
if ($Remove) { & $script:adb -s $script:Serial uninstall $pkg; exit }

$root = Split-Path $PSScriptRoot -Parent
if (-not $Apk) { $Apk = "$root\app\build-bench\outputs\apk\debug\app-armeabi-v7a-debug.apk" }
& $script:adb -s $script:Serial install -r $Apk | Out-Null

$dir = "$root\build\lists"
New-Item -ItemType Directory -Force $dir | Out-Null
$name = "b$([int]($List / 1000))k.m3u"
if (-not (Test-Path "$dir\$name")) { & python -I -X utf8 "$PSScriptRoot\gen_m3u.py" $List "$dir\$name" }

# The stick fetches the list from this PC through the adb connection.
$server = Start-Process python -ArgumentList '-I', '-m', 'http.server', '8765', '--bind', '127.0.0.1', '--directory', "`"$dir`"" -WindowStyle Hidden -PassThru
try {
    Start-Sleep 2
    & $script:adb -s $script:Serial reverse tcp:8765 tcp:8765 | Out-Null
    Sh 'logcat -c' | Out-Null
    $mode = if ($Data) { 'data' } else { 'add' }
    Sh "am broadcast -n $pkg/com.faznet.tv.bench.BenchReceiver --es list $name --es mode $mode --ei rounds $Rounds" | Out-Null
    $deadline = (Get-Date).AddSeconds(240)
    do { Start-Sleep 3; $done = [int](Sh "logcat -d -s FZB:I FZB:E | grep -c 'DONE'") } while ($done -lt 1 -and (Get-Date) -lt $deadline)
    Sh 'logcat -d -s FZB:I FZB:E' | Where-Object { $_ -match 'FZB' } | ForEach-Object { ($_ -split 'FZB\s*:\s*', 2)[1] }
} finally {
    & $script:adb -s $script:Serial reverse --remove tcp:8765 | Out-Null
    Stop-Process -Id $server.Id -ErrorAction SilentlyContinue
}
