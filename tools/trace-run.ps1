# Needs a build made with -PplayerTrace. Streams the player's trace lines to a file on the PC while channels are switched:
# the stick's own log is too small to read them afterwards.
#   tools\trace-run.ps1 -From 7 -Count 20 -Rounds 2 -Out build\trace.log
param([int]$From = 0, [int]$Count = 20, [int]$Rounds = 1, [int]$WatchSec = 3, [string]$Out = 'build\trace.log')
. "$PSScriptRoot\common.ps1"
Assert-StickFree
$p = Start-Process $script:adb -ArgumentList '-s', $script:Serial, 'logcat', '-v', 'time', '-s', 'FaznetPerf:I' -RedirectStandardOutput $Out -WindowStyle Hidden -PassThru
try {
    for ($r = 1; $r -le $Rounds; $r++) {
        if ($From -gt 0) {
            foreach ($d in "$From".ToCharArray()) { Sh "input keyevent KEYCODE_$d" | Out-Null }
            Sh 'input keyevent DPAD_CENTER' | Out-Null
            Start-Sleep 6
        }
        & "$PSScriptRoot\zap-test.ps1" -Count $Count -WatchSec $WatchSec | Select-Object -Last 4
    }
} finally {
    Stop-Process -Id $p.Id -ErrorAction SilentlyContinue
}
