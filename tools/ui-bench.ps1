# Home screen speed on the stick: cold start, frame times while the remote moves through the channels (one press at a
# time, a held-down key, presses as fast as the app takes them) and over the groups, then the app's own timings.
# Run it 2-3 times and compare. Compare the number of late frames, not only the percentage: a shorter scroll animation
# means fewer frames in total, which raises the percentage without anything getting worse.
#   tools\ui-bench.ps1 -Label before
#   tools\ui-bench.ps1 -Package com.faznet.tv.bench -Label 50k
param([string]$Package = 'com.faznet.tv', [string]$Label = 'run', [int]$Steps = 30)
. "$PSScriptRoot\common.ps1"
Assert-StickFree

Sh "am force-stop $Package" | Out-Null
Start-Sleep 1
Sh "logcat -c" | Out-Null
$start = Sh "am start -W -n $Package/com.faznet.tv.ui.MainActivity"
$total = ($start | Select-String 'TotalTime: (\d+)').Matches.Groups[1].Value
Start-Sleep 5  # the list opens from the copy on the device; give the first frame and logos time
"== $Label  cold start $total ms  PSS $(PssMb $Package) MB"

Sh "dumpsys gfxinfo $Package reset" | Out-Null
Keys DPAD_DOWN $Steps
Keys DPAD_UP $Steps
Frames $Package 'grid'

if ($Package -like '*.bench') {
    Sh "dumpsys gfxinfo $Package reset" | Out-Null
    Hold $Package 20 6000  # KEYCODE_DPAD_DOWN
    Hold $Package 19 6000  # KEYCODE_DPAD_UP
    Frames $Package 'held'
}

Sh "dumpsys gfxinfo $Package reset" | Out-Null
Burst DPAD_DOWN 80
Start-Sleep 2
Burst DPAD_UP 80
Start-Sleep 2
Frames $Package 'burst'

Sh "dumpsys gfxinfo $Package reset" | Out-Null
Keys DPAD_LEFT 4
Keys DPAD_DOWN 10
Keys DPAD_UP 10
Frames $Package 'groups'
"PSS after $(PssMb $Package) MB"
PerfLines | Where-Object { $_ -notmatch '^home.filter' } | Select-Object -First 8
"home.filter (group change): " + ((PerfLines | Where-Object { $_ -match '^home.filter (\d+)' } | ForEach-Object { [int]($_ -replace '\D', '') } | Measure-Object -Maximum -Average) | ForEach-Object { "avg $([math]::Round($_.Average)) ms, max $($_.Maximum) ms, n=$($_.Count)" })
