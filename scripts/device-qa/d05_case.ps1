# Type one D05 dev pinyin case into the P0 test page and capture the candidate UI.
# Usage: pwsh -NoProfile -File scripts/device-qa/d05_case.ps1 -InputStr "niyao..." -Tag d05-... [-Expand] [-Serial fe4ab855]
param(
  [Parameter(Mandatory=$true)][string]$InputStr,
  [Parameter(Mandatory=$true)][string]$Tag,
  [switch]$Expand,
  [string]$Serial = "fe4ab855",
  [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/device-qa/d05')
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }
# Key centers for 1080x2400 portrait; measured on the D05 builds (same keyboard geometry as Phase-1).
$KEY = @{
  q="61 1665"; w="168 1665"; e="275 1665"; r="382 1665"; t="488 1665"; y="594 1665"; u="700 1665"; i="806 1665"; o="912 1665"; p="1018 1665";
  a="106 1841"; s="215 1841"; d="324 1841"; f="433 1841"; g="541 1841"; h="650 1841"; j="757 1841"; k="866 1841"; l="974 1841";
  z="222 2017"; x="328 2017"; c="434 2017"; v="540 2017"; b="646 2017"; n="752 2017"; m="858 2017";
  shift="88 2017"; backspace="991 2017";
  comma="293 2196"; space="590 2196"; period="887 2196"; enter="1004 2196";
  expand="740 1494"; ai="871 1494"; gear="1004 1494"
}
function Shot([string]$name) {
  $png = Join-Path $EvidenceDir "$Tag-$name.png"
  adb -s $Serial shell screencap -p /sdcard/d05shot.png | Out-Null
  adb -s $Serial pull /sdcard/d05shot.png $png 2>$null | Out-Null
  Write-Output "screenshot: $png"
}
function Dump([string]$name) {
  $xml = Join-Path $EvidenceDir "$Tag-$name.xml"
  adb -s $Serial shell uiautomator dump /sdcard/d05ui.xml | Out-Null
  adb -s $Serial pull /sdcard/d05ui.xml $xml 2>$null | Out-Null
  [xml](Get-Content -Path $xml -Raw -Encoding UTF8)
}
Write-Output "== D05 case tag=$Tag input=$InputStr (len=$($InputStr.Length)) =="
adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
adb -s $Serial shell wm dismiss-keyguard | Out-Null
adb -s $Serial shell settings put system accelerometer_rotation 0 | Out-Null
adb -s $Serial shell settings put system user_rotation 0 | Out-Null
adb -s $Serial shell am force-stop mark.via | Out-Null
Start-Sleep -Milliseconds 500
adb -s $Serial shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:8769/p0.html" -n mark.via/.Shell | Out-Null
Start-Sleep -Seconds 3
adb -s $Serial shell input tap 540 600 | Out-Null
Start-Sleep -Milliseconds 1200
# clear any previous text
adb -s $Serial shell input tap 121 1009 | Out-Null
Start-Sleep -Milliseconds 400
adb -s $Serial shell input tap 540 600 | Out-Null
Start-Sleep -Milliseconds 800
$parts = @()
foreach ($ch in $InputStr.ToCharArray()) {
  $k = [string]$ch
  if (-not $KEY.ContainsKey($k)) { throw "unsupported key: $k" }
  $parts += "input tap $($KEY[$k])"
}
$cmd = ($parts -join "; sleep 0.11; ")
adb -s $Serial shell $cmd | Out-Null
Start-Sleep -Milliseconds 2200
Shot "01-collapsed"
$doc = Dump "01-collapsed"
$edit = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" } | Select-Object -First 1
$status = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "len=*" } | Select-Object -First 1
Write-Output ("collapsed status: " + $(if($status){$status.GetAttribute('text')}else{'n/a'}))
if ($Expand) {
  adb -s $Serial shell input tap 740 1494 | Out-Null
  Start-Sleep -Milliseconds 800
  Shot "02-expanded"
  $doc2 = Dump "02-expanded"
  $status2 = $doc2.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "len=*" } | Select-Object -First 1
  Write-Output ("expanded status: " + $(if($status2){$status2.GetAttribute('text')}else{'n/a'}))
}
