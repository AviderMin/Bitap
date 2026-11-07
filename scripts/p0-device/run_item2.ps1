# Item 2: landscape full flow (portrait -> landscape -> portrait).
# Steps: rotate, open local page, input long sentence, commit candidate, backspace,
# retype correction, expand/collapse candidates, rotate back and verify text.
# Key coordinates are DETECTED from a screenshot via detect_keys.py, because the
# IME window height/offset can vary between sessions. Reference tables for the
# default layout are in detect_keys.py (portrait 1080x2400 / landscape 2400x1080).
param(
  [string]$EvidenceDir = "D:\Projects\Github\Bitap\.tmp\p0final\precheck-item2",
  [string]$Tag = "precheck-item2",
  [string]$BaseUrl = "http://127.0.0.1:8769"
)
$ErrorActionPreference = "Stop"
$serial = if ($env:BITAP_DEVICE) { $env:BITAP_DEVICE } else { "fe4ab855" }
$py = "C:\Users\AviderMin\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
$dump = Join-Path $PSScriptRoot "dump_ui.ps1"
$shot = Join-Path $PSScriptRoot "screenshot.ps1"
$detect = Join-Path $PSScriptRoot "detect_keys.py"
$ev = $EvidenceDir
if (-not (Test-Path $ev)) { New-Item -ItemType Directory -Force -Path $ev | Out-Null }

function Get-Field($xmlPath) {
  [xml]$doc = Get-Content -Path $xmlPath -Raw -Encoding UTF8
  $edit = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" } | Select-Object -First 1
  $status = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "len=*" } | Select-Object -First 1
  return [pscustomobject]@{ Text=if($edit){$edit.GetAttribute("text")}else{$null}; Status=if($status){$status.GetAttribute("text")}else{$null}; Bounds=if($edit){$edit.GetAttribute("bounds")}else{$null} }
}
function Get-EditCenter($xmlPath) {
  $f = Get-Field $xmlPath
  if ($f.Bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
    return @([int](([int]$matches[1]+[int]$matches[3])/2), [int](([int]$matches[2]+[int]$matches[4])/2))
  }
  return @(540,600)
}
function Save-Step([string]$name) {
  $xml = Join-Path $ev "$Tag-$name.xml"; $png = Join-Path $ev "$Tag-$name.png"
  & $dump -Out $xml | Out-Null; & $shot -Out $png | Out-Null
  $f = Get-Field $xml
  Write-Host ("STEP {0,-22} text='{1}'" -f $name, $f.Text)
  return [string]$f.Text
}
function Update-Keys {
  for ($i=0; $i -lt 6; $i++) {
    $png = Join-Path $ev "$Tag-kb-layout.png"
    & $shot -Out $png | Out-Null
    $json = & $py $detect $png 2>$null
    if ($LASTEXITCODE -eq 0 -and $json -notmatch '"error"') { return ($json | ConvertFrom-Json) }
    Start-Sleep -Milliseconds 600
  }
  throw "detect_keys failed: $json"
}
function Use-Keyboard {
  $xml = Join-Path $ev "$Tag-kb-layout.xml"
  & $dump -Out $xml | Out-Null
  $c = Get-EditCenter $xml
  adb -s $serial shell input tap $c[0] $c[1] | Out-Null
  Start-Sleep -Milliseconds 1200
  return (Update-Keys)
}
function Tap-Key($K, [string]$name) {
  $p = $K.$name
  if (-not $p) { throw "key $name not detected" }
  adb -s $serial shell input tap $p | Out-Null
  Start-Sleep -Milliseconds 120
}
function Type-Keys($K, [string[]]$names, [int]$gapMs=120) {
  $parts = @(); foreach ($n in $names) { $parts += "input tap $($K.$n)" }
  adb -s $serial shell ($parts -join ("; sleep " + ($gapMs/1000.0).ToString("0.00") + "; ")) | Out-Null
}
function Get-KeyboardRatio {
  $disp = adb -s $serial shell dumpsys display | Select-String -Pattern 'mCurrentOrientation' | Select-Object -First 1
  $land = ($disp -match 'mCurrentOrientation=1') -or ($disp -match 'mCurrentOrientation=3')
  $screenH = if ($land) { 1080 } else { 2400 }
  $lines = adb -s $serial shell dumpsys window windows
  $idx = ($lines | Select-String -Pattern 'Window #.*InputMethod').LineNumber
  if (-not $idx) { return "IME window not found" }
  $block = $lines[($idx-1)..([Math]::Min($idx+30, $lines.Count-1))]
  $frameLine = $block | Select-String -Pattern 'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]' | Select-Object -First 1
  if ($frameLine -and $frameLine.Matches.Count -gt 0) {
    $m = $frameLine.Matches[0]
    $h = [int]$m.Groups[4].Value - [int]$m.Groups[2].Value
    $w = [int]$m.Groups[3].Value - [int]$m.Groups[1].Value
    return ("IME frame {0}x{1}, screenHeight={2}, height_ratio={3:P1}" -f $w, $h, $screenH, ($h/[double]$screenH))
  }
  return "frame line not parsed"
}

Write-Output "== Item2 landscape flow: evidence=$ev =="
adb -s $serial shell am force-stop mark.via | Out-Null
Start-Sleep -Milliseconds 500
adb -s $serial shell settings put system accelerometer_rotation 0 | Out-Null
adb -s $serial shell settings put system user_rotation 0 | Out-Null
Start-Sleep -Seconds 2
adb -s $serial shell am start -a android.intent.action.VIEW -d "$BaseUrl/p0.html?item=2" -n mark.via/.Shell | Out-Null
Start-Sleep -Seconds 2
adb -s $serial shell settings put system user_rotation 1 | Out-Null
Start-Sleep -Seconds 2

$K = Use-Keyboard
$r1 = Save-Step "01-landscape-keyboard"
Write-Output ("KEYBOARD-RATIO " + (Get-KeyboardRatio))

# long sentence jintiantianqihenhao
Type-Keys $K @("j","i","n","t","i","a","n","t","i","a","n","q","i","h","e","n","h","a","o")
Start-Sleep -Milliseconds 1200
$t1 = Save-Step "02-long-candidates"
Write-Output ("KEYBOARD-RATIO-COMPOSING " + (Get-KeyboardRatio))
$K = Update-Keys
Tap-Key $K "space"
Start-Sleep -Milliseconds 1200
$t2 = Save-Step "03-long-committed"
$K = Update-Keys
Tap-Key $K "backspace"
Start-Sleep -Milliseconds 600
$t3 = Save-Step "04-backspace"
Type-Keys $K @("h","a","o")
Start-Sleep -Milliseconds 300
Tap-Key $K "space"
Start-Sleep -Milliseconds 1200
$t4 = Save-Step "05-retype-committed"
# expand / collapse with nihao composing
Type-Keys $K @("n","i","h","a","o")
Start-Sleep -Milliseconds 1200
$t5 = Save-Step "06-before-expand"
$K = Update-Keys
Tap-Key $K "expand"
Start-Sleep -Milliseconds 1000
$t6 = Save-Step "07-expanded"
# expanded overlay covers the key area: keep the candidate-bar coordinates detected before expanding
Tap-Key $K "expand"
Start-Sleep -Milliseconds 1000
$t7 = Save-Step "08-collapsed"
$K = Update-Keys
Tap-Key $K "space"
Start-Sleep -Milliseconds 1200
$t7b = Save-Step "08b-space-after-collapse"
# rotate back portrait
adb -s $serial shell settings put system user_rotation 0 | Out-Null
Start-Sleep -Seconds 2
$K2 = Use-Keyboard
$t8 = Save-Step "09-portrait-restored"

Write-Output ""
Write-Output "== Item2 summary =="
Write-Output ("[1] landscape keyboard: {0}" -f $r1)
Write-Output ("[2] long commit: '{0}' (expect 今天天气很好)" -f $t2)
Write-Output ("[3] backspace: '{0}' (expect 今天天气很)" -f $t3)
Write-Output ("[4] retype: '{0}' (expect 今天天气很好)" -f $t4)
Write-Output ("[5] expand/collapse: before='{0}' expanded='{1}' collapsed='{2}' space_after='{3}'" -f $t5,$t6,$t7,$t7b)
Write-Output ("[6] portrait restored: '{0}'" -f $t8)
