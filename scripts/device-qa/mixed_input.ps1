# Bitap on-device D07 mixed Chinese/English/number input check.
# Keyboard key centers are measured for 22021211RC/1080x2400@440 portrait and
# must be re-measured if the layout changes. Run with PowerShell 7:
#   pwsh -NoProfile -File scripts/device-qa/mixed_input.ps1 -Tag mixed
param(
  [string]$Serial = "fe4ab855",
  [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/device-qa/mixed-input'),
  [string]$Tag = "mixed",
  [string]$BaseUrl = "http://127.0.0.1:8769"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }
$KEY = @{
  textarea = "540 600"
  shift = "88 2017"
  layerSwitch = "66 2196"
  space = "589 2196"
  firstCandidate = "85 1494"
  q="61 1665"; w="168 1665"; e="275 1665"; r="382 1665"; t="488 1665"; y="594 1665"; u="700 1665"; i="806 1665"; o="912 1665"; p="1018 1665";
  a="106 1841"; s="215 1841"; d="324 1841"; f="433 1841"; g="541 1841"; h="650 1841"; j="757 1841"; k="866 1841"; l="974 1841";
  z="222 2017"; x="328 2017"; c="434 2017"; v="540 2017"; b="646 2017"; n="752 2017"; m="858 2017";
  digit1="57 1665"; digit2="164 1665"; digit3="272 1665"; digit4="379 1665"; digit5="486 1665"; digit6="594 1665"
}
$results = @()
function Add-Result([string]$name, [bool]$pass, [string]$detail) {
  $script:results += [pscustomobject]@{ Step = $name; Verdict = $(if ($pass) { "PASS" } else { "FAIL" }); Detail = $detail }
}
function Invoke-Adb([string[]]$arguments) {
  $out = & adb -s $Serial @arguments 2>&1
  if ($LASTEXITCODE -ne 0) { throw ("adb failed: " + ($arguments -join ' ') + " => " + ($out | Out-String)) }
  return ($out | Out-String)
}
function Tap-Key([string]$name, [int]$settleMs = 220) {
  $parts = $KEY[$name].Split(" ")
  Invoke-Adb @("shell", "input", "tap", $parts[0], $parts[1]) | Out-Null
  Start-Sleep -Milliseconds $settleMs
}
function Type-Key([string]$name, [int]$settleMs = 90) { Tap-Key $name $settleMs }
function Get-Ui([string]$xmlPath) {
  Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", "/sdcard/bitap_mixed_ui.xml") | Out-Null
  Invoke-Adb @("pull", "/sdcard/bitap_mixed_ui.xml", $xmlPath) | Out-Null
  [xml](Get-Content -Path $xmlPath -Raw -Encoding UTF8)
}
function Get-Status($doc) {
  $node = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "len=*" } | Select-Object -First 1
  if (-not $node) { return $null }
  $text = [string]$node.GetAttribute("text")
  $m = [regex]::Match($text, '^len=(\d+) sel=(\d+)-(\d+) value=(.*)$')
  if (-not $m.Success) { return $null }
  $value = $null
  try { $value = ($m.Groups[4].Value | ConvertFrom-Json) } catch { $value = $m.Groups[4].Value }
  [pscustomobject]@{ Text = $text; Len = [int]$m.Groups[1].Value; Value = $value }
}
function Save-Step([string]$name) {
  $png = Join-Path $EvidenceDir "$Tag-$name.png"
  Invoke-Adb @("shell", "screencap", "-p", "/sdcard/bitap_mixed.png") | Out-Null
  Invoke-Adb @("pull", "/sdcard/bitap_mixed.png", $png) | Out-Null
  $doc = Get-Ui (Join-Path $EvidenceDir "$Tag-$name.xml")
  $status = Get-Status $doc
  if ($status) { Write-Host ("STEP {0,-28} {1}" -f $name, $status.Text) }
  [pscustomobject]@{ Status = $status }
}
function Focus-Textarea { Tap-Key "textarea" 700 }
function Clear-Page {
  $clear = $null
  for ($i = 0; $i -lt 10; $i++) {
    $doc = Get-Ui (Join-Path $EvidenceDir "$Tag-clear-$i.xml")
    $clear = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -eq "清空" } | Select-Object -First 1
    if ($clear) { break }
    Start-Sleep -Milliseconds 700
  }
  if (-not $clear) { throw "page clear button not found" }
  $nums = [regex]::Matches($clear.GetAttribute("bounds"), '\d+') | ForEach-Object { [int]$_.Value }
  Invoke-Adb @("shell", "input", "tap", [string][int](($nums[0] + $nums[2]) / 2), [string][int](($nums[1] + $nums[3]) / 2)) | Out-Null
  Start-Sleep -Milliseconds 500
}
function Type-Letters([string]$text, [int]$settleMs = 90) {
  foreach ($char in $text.ToCharArray()) { Type-Key ([string]$char) $settleMs }
}
function Reset-Page {
  Clear-Page
  Focus-Textarea
}

Write-Output "== Bitap D07 mixed input device test tag=$Tag device=$Serial =="
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
Invoke-Adb @("shell", "wm", "dismiss-keyguard") | Out-Null
Invoke-Adb @("shell", "settings", "put", "system", "accelerometer_rotation", "0") | Out-Null
Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "0") | Out-Null
$port = ([uri]$BaseUrl).Port
Invoke-Adb @("reverse", "tcp:$port", "tcp:$port") | Out-Null
Invoke-Adb @("shell", "am", "force-stop", "com.bitap.keyboard") | Out-Null
Invoke-Adb @("shell", "ime", "set", "com.bitap.keyboard/.ime.BitapImeService") | Out-Null
Start-Sleep -Milliseconds 700
Invoke-Adb @("shell", "am", "force-stop", "mark.via") | Out-Null
Start-Sleep -Milliseconds 500
Invoke-Adb @("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "$BaseUrl/p0.html", "-n", "mark.via/.Shell") | Out-Null
Start-Sleep -Seconds 3
Focus-Textarea
Reset-Page

# 1) Chinese-mode literal run: Shift + android + number-layer 16, then space.
Tap-Key "shift" 250
Type-Letters "android"
Tap-Key "layerSwitch" 500
Tap-Key "digit1" 250
Tap-Key "digit6" 250
$s = Save-Step "01-literal-android16"
Add-Result "literal-android16" ($s.Status -and $s.Status.Value -eq "Android16") ("status=" + $s.Status.Text)
Tap-Key "space" 450
$s = Save-Step "02-literal-space"
Add-Result "literal-space" ($s.Status -and $s.Status.Value -eq "Android16 ") ("status=" + $s.Status.Text)

# 2) Pure pinyin must remain pure pinyin: fangan -> 方案.
Reset-Page
Type-Letters "fangan" 110
Start-Sleep -Milliseconds 1200
Tap-Key "firstCandidate" 700
$s = Save-Step "03-pure-pinyin"
Add-Result "pure-pinyin-unchanged" ($s.Status -and $s.Status.Value -eq "方案") ("status=" + $s.Status.Text)

# 3) Continuous pinyin prefix + English suffix candidate: daimajianchayongsonar -> 代码检查用Sonar.
Reset-Page
Type-Letters "daimajianchayongsonar" 110
Start-Sleep -Milliseconds 1600
Tap-Key "firstCandidate" 800
$s = Save-Step "04-mixed-candidate"
Add-Result "mixed-candidate" ($s.Status -and $s.Status.Value -eq "代码检查用Sonar") ("status=" + $s.Status.Text)

# 4) Whole-token English fallback: android -> Android.
Reset-Page
Type-Letters "android" 100
Start-Sleep -Milliseconds 1200
Tap-Key "firstCandidate" 700
$s = Save-Step "05-whole-english"
Add-Result "whole-token-english" ($s.Status -and $s.Status.Value -eq "Android") ("status=" + $s.Status.Text)

# 5) Sentence-final particle shorthand: nihaom -> 你好吗, zenml -> 怎么了.
Reset-Page
Type-Letters "nihaom" 100
Start-Sleep -Milliseconds 1200
Tap-Key "firstCandidate" 700
$s = Save-Step "06-particle-ma"
Add-Result "particle-ma" ($s.Status -and $s.Status.Value -eq "你好吗") ("status=" + $s.Status.Text)

Reset-Page
Type-Letters "zenml" 100
Start-Sleep -Milliseconds 1200
Tap-Key "firstCandidate" 700
$s = Save-Step "07-particle-le"
Add-Result "particle-le" ($s.Status -and $s.Status.Value -eq "怎么了") ("status=" + $s.Status.Text)

Reset-Page
Type-Letters "nihaob" 100
Start-Sleep -Milliseconds 1200
Tap-Key "firstCandidate" 700
$s = Save-Step "08-particle-ba"
Add-Result "particle-ba" ($s.Status -and $s.Status.Value -eq "你好吧") ("status=" + $s.Status.Text)

Write-Output ""
Write-Output "== Summary =="
foreach ($r in $results) { Write-Output ("[{0}] {1}: {2}" -f $r.Verdict, $r.Step, $r.Detail) }
$failed = @($results | Where-Object { $_.Verdict -eq "FAIL" }).Count
Write-Output ("TOTAL={0} PASS={1} FAIL={2}" -f $results.Count, ($results.Count - $failed), $failed)
if ($failed -gt 0) { exit 1 }
