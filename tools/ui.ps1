# Small UI driver for the stick: list visible texts, tap an element by its text, take a screenshot.
#   tools\ui.ps1 -List            every text and description on screen with its centre
#   tools\ui.ps1 -Tap "Settings"  taps the first element that contains it (taps put the app in touch mode: focus behaves
#                                 differently from a remote, so use keys when focus is what is being tested)
#   tools\ui.ps1 -Shot name       build\test-shots\name.png
param([switch]$List, [string]$Tap, [string]$Shot)
. "$PSScriptRoot\common.ps1"

function Dump {
    Sh 'uiautomator dump /sdcard/ui.xml' | Out-Null
    [xml](Sh 'cat /sdcard/ui.xml')
}
function Nodes($x) {
    $x.SelectNodes('//node') | Where-Object { $_.text -or $_.'content-desc' } | ForEach-Object {
        $b = [regex]::Matches($_.bounds, '\d+') | ForEach-Object { [int]$_.Value }
        [pscustomobject]@{ text = ($_.text + ' ' + $_.'content-desc').Trim(); x = [int](($b[0] + $b[2]) / 2); y = [int](($b[1] + $b[3]) / 2) }
    }
}
if ($List) { Nodes (Dump) | ForEach-Object { '{0,5},{1,-5} {2}' -f $_.x, $_.y, $_.text } }
if ($Tap) {
    $n = Nodes (Dump) | Where-Object { $_.text.Contains($Tap) } | Select-Object -First 1
    if (-not $n) { "not found: $Tap"; exit 1 }
    Sh "input tap $($n.x) $($n.y)" | Out-Null
    "tapped '$($n.text)' at $($n.x),$($n.y)"
}
if ($Shot) {
    $dir = Join-Path $PSScriptRoot '..\build\test-shots'
    New-Item -ItemType Directory -Force $dir | Out-Null
    Sh 'screencap -p /sdcard/s.png' | Out-Null
    & $script:adb -s $script:Serial pull /sdcard/s.png "$dir\$Shot.png" | Out-Null
    (Resolve-Path "$dir\$Shot.png").Path
}
