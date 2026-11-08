# Item 4: AI loading + cursor move / selection change / cancel.
# Portrait 1080x2400, Bitap keyboard, AI lexicon path (type pingguo, tap AI).
# During loading: (a) tap editor (300,600) to move cursor, (b) long-press editor
# (300,450,900ms) to change selection, (c) tap "取消 AI" in candidate bar.
# After each action wait 20s+ and verify no loading remains and text unchanged.
# Key coordinates are detected from a screenshot via detect_keys.py.
param(
  [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/p0final/precheck-item4'),
  [string]$Tag = "precheck-item4",
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
  Write-Host ("STEP {0,-28} text='{1}' status='{2}'" -f $name, $f.Text, $f.Status)
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
function Type-Keys($K, [string[]]$names, [int]$gapMs=120) {
  $parts = @(); foreach ($n in $names) { $parts += "input tap $($K.$n)" }
  adb -s $serial shell ($parts -join ("; sleep " + ($gapMs/1000.0).ToString("0.00") + "; ")) | Out-Null
}
function Capture-Loading($K, [string]$name) {
  # tap AI and grab the device screen very soon after, in one adb shell to avoid round-trip delay
  adb -s $serial shell "input tap $($K.ai); sleep 0.25; screencap -p /sdcard/p0_ai_load.png" | Out-Null
  $png = Join-Path $ev "$Tag-$name.png"
  adb -s $serial pull /sdcard/p0_ai_load.png "$png" 2>$null | Out-Null
  $xml = Join-Path $ev "$Tag-$name.xml"
  & $dump -Out $xml | Out-Null
  $f = Get-Field $xml
  Write-Host ("STEP {0,-28} text='{1}' status='{2}'" -f $name, $f.Text, $f.Status)
}
function Reset-Pingguo($K) {
  adb -s $serial shell input tap 121 1009 | Out-Null
  Start-Sleep -Milliseconds 500
  adb -s $serial shell input tap 540 600 | Out-Null
  Start-Sleep -Milliseconds 800
  Type-Keys $K @("p","i","n","g","g","u","o")
  Start-Sleep -Milliseconds 1800
}

Write-Output "== Item4 AI cancel: evidence=$ev =="
adb -s $serial shell am force-stop mark.via | Out-Null
Start-Sleep -Milliseconds 500
adb -s $serial shell settings put system user_rotation 0 | Out-Null
Start-Sleep -Seconds 1
adb -s $serial shell am start -a android.intent.action.VIEW -d "$BaseUrl/p0.html?item=4" -n mark.via/.Shell | Out-Null
Start-Sleep -Seconds 2
adb -s $serial shell input tap 540 600 | Out-Null
Start-Sleep -Milliseconds 900
$K = Update-Keys

# (a) cursor move during loading
Reset-Pingguo $K
$a0 = Save-Step "a0-before-ai"
Capture-Loading $K "a1-loading"
adb -s $serial shell input tap 300 600 | Out-Null
Start-Sleep -Seconds 3
$a2 = Save-Step "a2-after-cursor-3s"
Start-Sleep -Seconds 20
$a3 = Save-Step "a3-after-cursor-23s"

# (b) selection change during loading
Reset-Pingguo $K
$b0 = Save-Step "b0-before-ai"
Capture-Loading $K "b1-loading"
adb -s $serial shell input swipe 300 450 300 450 900 | Out-Null
Start-Sleep -Seconds 2
$b2 = Save-Step "b2-after-selection-2s"
Start-Sleep -Seconds 20
$b3 = Save-Step "b3-after-selection-22s"

# (c) cancel via "取消 AI" during loading (same entry as AI button)
Reset-Pingguo $K
$c0 = Save-Step "c0-before-ai"
Capture-Loading $K "c1-loading"
adb -s $serial shell input tap $($K.ai) | Out-Null
Start-Sleep -Seconds 2
$c2 = Save-Step "c2-after-cancel-2s"
Start-Sleep -Seconds 21
$c3 = Save-Step "c3-after-cancel-23s"

Write-Output ""
Write-Output "== Item4 summary =="
Write-Output ("a: before='{0}' 3s='{1}' 23s='{2}'" -f $a0,$a2,$a3)
Write-Output ("b: before='{0}' 2s='{1}' 22s='{2}'" -f $b0,$b2,$b3)
Write-Output ("c: before='{0}' 2s='{1}' 23s='{2}'" -f $c0,$c2,$c3)
