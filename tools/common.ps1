# Shared by the scripts in tools/. The stick's address stays out of the repository: set it once per terminal,
#   $env:FAZNET_STICK = '<ip>:5555'
$script:adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not $env:FAZNET_STICK) { throw 'Set $env:FAZNET_STICK to the stick address first (ip:5555).' }
$script:Serial = $env:FAZNET_STICK

function Sh([string]$c) { & $script:adb -s $script:Serial shell $c }

# Key presses one call at a time (what a person does, slowly) or all in one call (a held-down remote).
function Keys([string]$key, [int]$n) { Sh ("for i in `$(seq $n); do input keyevent $key; done") | Out-Null }
function Burst([string]$key, [int]$n) { Sh ("input keyevent " + (@($key) * $n -join ' ')) | Out-Null }

# A key really held down, as on the remote. adb has no way to do this, so only the bench app can: it sends the same
# key events the system would (a repeat every 50 ms after half a second) into its own window.
function Hold([string]$pkg, [int]$keyCode, [int]$ms) {
    Sh "am broadcast -n $pkg/com.faznet.tv.bench.BenchReceiver --es mode hold --ei key $keyCode --ei ms $ms" | Out-Null
    Start-Sleep -Milliseconds ($ms + 1000)
}

function Assert-StickFree {
    $focus = (Sh "dumpsys window | grep mCurrentFocus") -join ' '
    $playing = Sh "dumpsys media_session | grep -c 'state=PlaybackState {state=3'"
    if ($focus -notmatch 'faznet' -and [int]$playing -gt 0) {
        throw "The stick is playing something else ($($focus.Trim())). Not touching it."
    }
}

# Frame numbers from gfxinfo since the last reset, as one line.
function Frames([string]$pkg, [string]$label) {
    $g = Sh "dumpsys gfxinfo $pkg"
    function N($pat) { ($g | Select-String $pat | Select-Object -First 1).Matches.Groups[1].Value }
    "{0,-8} frames {1,5} | late {2,6}% | p50 {3,3} p90 {4,3} p95 {5,3} p99 {6,3} ms | slow UI {7,4} | missed {8,4}" -f $label,
        (N 'Total frames rendered: (\d+)'), (N 'Janky frames: \d+ \(([\d.]+)%\)'), (N '50th percentile: (\d+)ms'),
        (N '90th percentile: (\d+)ms'), (N '95th percentile: (\d+)ms'), (N '99th percentile: (\d+)ms'),
        (N 'Number Slow UI thread: (\d+)'), (N 'Number Frame deadline missed: (\d+)')
}

function PssMb([string]$pkg) {
    $m = (Sh "dumpsys meminfo $pkg" | Select-String 'TOTAL PSS:\s+(\d+)' | Select-Object -First 1).Matches.Groups[1].Value
    [math]::Round([int]$m / 1024)
}

# Lines this app logged with tools' tag since the log was last cleared.
function PerfLines { Sh "logcat -d -s FaznetPerf:I" | Where-Object { $_ -match 'FaznetPerf' } | ForEach-Object { ($_ -split 'FaznetPerf\s*:\s*', 2)[1] } }
