# Channel switching on the stick, with the app's own timings (tag FaznetPerf, "open ..." lines).
# Open a channel in Faznet first (or pass -Open to press OK on the focused one), then this presses Up N times, waits for
# each channel to appear and prints how long that took, how often a channel needed re-opening, and memory at both ends.
#   tools\zap-test.ps1 -Count 20
param([int]$Count = 20, [switch]$Open, [string]$Package = 'com.faznet.tv', [int]$TimeoutSec = 15, [int]$WatchSec = 3)
. "$PSScriptRoot\common.ps1"
Assert-StickFree

if ($Open) { Sh 'input keyevent DPAD_CENTER' | Out-Null; Start-Sleep 8 }
$focus = (Sh 'dumpsys window | grep mCurrentFocus') -join ' '
if ($focus -notmatch 'PlayerActivity') { throw "Faznet's player is not open: $focus" }

function ObjectCount([string]$name) {
    $m = Sh "dumpsys meminfo $Package" | Select-String "$name\s*:\s*(\d+)" | Select-Object -First 1
    if ($m) { $m.Matches.Groups[1].Value } else { '?' }
}

Sh 'logcat -c' | Out-Null
$pss0 = PssMb $Package
$opens = New-Object System.Collections.ArrayList
$seen = @{}
# The stick's log is small and the kernel fills it quickly, so every line is collected as soon as it shows up.
function Collect {
    $new = 0
    foreach ($l in (Sh 'logcat -d -v time -s FaznetPerf:I')) {
        if ($l -notmatch 'FaznetPerf[^:]*:\s*(open .*)' -or $seen.ContainsKey($l)) { continue }
        $seen[$l] = $true
        [void]$opens.Add($Matches[1])
        $new++
    }
    $new
}
$missed = 0
for ($i = 1; $i -le $Count; $i++) {
    Sh 'input keyevent DPAD_UP' | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $got = 0
    do {
        Start-Sleep -Milliseconds 500
        $got += Collect
    } while ($got -eq 0 -and (Get-Date) -lt $deadline)
    if ($got -eq 0) { $missed++; "step $i : nothing appeared in $TimeoutSec s" }
    Start-Sleep $WatchSec  # watch it for a moment, as a person would
}

$opens | ForEach-Object { $_ }
function Num($key) { $opens | ForEach-Object { if ($_ -match "\b$key=(-?\d+)") { [int]$Matches[1] } } | Where-Object { $_ -ge 0 } | Sort-Object }
function Pct($sorted, [double]$p) { if ($sorted.Count -eq 0) { return '-' } $sorted[[math]::Min($sorted.Count - 1, [math]::Floor($sorted.Count * $p))] }
$total = @(Num 'total'); $ready = @(Num 'ready'); $frame = @(Num 'frame'); $show = @(Num 'show'); $prep = @(Num 'prepare')
$reopened = @($opens | Where-Object { $_ -match 'reopens=([1-9]\d*)' }).Count
''
"switched $($opens.Count) channels of $Count, nothing appeared: $missed, needed a re-open: $reopened"
"press to picture  median $(Pct $total 0.5) ms   p90 $(Pct $total 0.9) ms   worst $(Pct $total 1) ms"
"after the wait:   prepared $(Pct $prep 0.5)   ready $(Pct $ready 0.5)   first frame $(Pct $frame 0.5)   shown $(Pct $show 0.5) ms (medians)"
"PSS $pss0 MB -> $(PssMb $Package) MB   Activities $(ObjectCount 'Activities')   ViewRootImpl $(ObjectCount 'ViewRootImpl')   Views $(ObjectCount 'Views')"
