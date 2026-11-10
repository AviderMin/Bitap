# Bitap on-device English completion/next-word check. Keyboard key centers are
# measured for 22021211RC/1080x2400@440 portrait and must be re-measured if the
# layout changes.
param(
  [string]$Serial = "fe4ab855",
  [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/device-qa/english-prediction'),
  [string]$Tag = "english",
  [string]$BaseUrl = "http://127.0.0.1:8769"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }
$KEY = @{
  textarea = "540 600"
  toggleLanguage = "191 2210"
  h = "650 1841"
  e = "275 1665"
  l = "974 1841"
  t = "488 1665"
  a = "106 1841"
  n = "752 2017"
  k = "866 1841"
  space = "590 2210"
  firstSuggestion = "170 1494"
  nextSuggestion = "120 1494"
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
function Tap-Key([string]$name, [int]$settleMs = 450) {
  $parts = $KEY[$name].Split(" ")
  Invoke-Adb @("shell", "input", "tap", $parts[0], $parts[1]) | Out-Null
  Start-Sleep -Milliseconds $settleMs
}
function Get-Ui([string]$xmlPath) {
  Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", "/sdcard/bitap_english_ui.xml") | Out-Null
  Invoke-Adb @("pull", "/sdcard/bitap_english_ui.xml", $xmlPath) | Out-Null
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
  Invoke-Adb @("shell", "screencap", "-p", "/sdcard/bitap_english.png") | Out-Null
  Invoke-Adb @("pull", "/sdcard/bitap_english.png", $png) | Out-Null
  $doc = Get-Ui (Join-Path $EvidenceDir "$Tag-$name.xml")
  $status = Get-Status $doc
  if ($status) { Write-Host ("STEP {0,-24} {1}" -f $name, $status.Text) }
  [pscustomobject]@{ Status = $status }
}
function Focus-Textarea {
  Tap-Key "textarea" 700
}
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

Write-Output "== Bitap English prediction device test tag=$Tag device=$Serial =="
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
Invoke-Adb @("shell", "wm", "dismiss-keyguard") | Out-Null
Invoke-Adb @("shell", "settings", "put", "system", "accelerometer_rotation", "0") | Out-Null
Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "0") | Out-Null
$port = ([uri]$BaseUrl).Port
Invoke-Adb @("reverse", "tcp:$port", "tcp:$port") | Out-Null
# Restart the IME process so the initial language is the default Chinese layout.
Invoke-Adb @("shell", "am", "force-stop", "com.bitap.keyboard") | Out-Null
Invoke-Adb @("shell", "ime", "set", "com.bitap.keyboard/.ime.BitapImeService") | Out-Null
Start-Sleep -Milliseconds 700
Invoke-Adb @("shell", "am", "force-stop", "mark.via") | Out-Null
Start-Sleep -Milliseconds 500
Invoke-Adb @("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "$BaseUrl/p0.html", "-n", "mark.via/.Shell") | Out-Null
Start-Sleep -Seconds 3
Focus-Textarea
Clear-Page
Focus-Textarea
Tap-Key "toggleLanguage" 700

Tap-Key "h" 200
Tap-Key "e" 200
Tap-Key "l" 900
$s = Save-Step "01-hel"
Add-Result "completion-visible" ($s.Status.Value -eq "hel") ("status=" + $s.Status.Text)
Tap-Key "firstSuggestion" 800
$s = Save-Step "02-completed"
Add-Result "completion-tap" ($s.Status.Value -eq "hello ") ("status=" + $s.Status.Text)

Tap-Key "t" 180
Tap-Key "h" 180
Tap-Key "a" 180
Tap-Key "n" 180
Tap-Key "k" 700
Tap-Key "space" 900
$s = Save-Step "03-next-word"
Add-Result "next-word-visible" ($s.Status.Value -eq "hello thank ") ("status=" + $s.Status.Text)
Tap-Key "nextSuggestion" 800
$s = Save-Step "04-next-word-tap"
Add-Result "next-word-tap" ($s.Status.Value -eq "hello thank you ") ("status=" + $s.Status.Text)

Write-Output ""
Write-Output "== Summary =="
foreach ($r in $results) { Write-Output ("[{0}] {1}: {2}" -f $r.Verdict, $r.Step, $r.Detail) }
$failed = @($results | Where-Object { $_.Verdict -eq "FAIL" }).Count
Write-Output ("TOTAL={0} PASS={1} FAIL={2}" -f $results.Count, ($results.Count - $failed), $failed)
if ($failed -gt 0) { exit 1 }
