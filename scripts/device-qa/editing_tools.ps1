# Bitap on-device editing-tools check: selection, copy, cut, paste, word cursor,
# word delete, and dedicated editing-page open/close. The Android IME window is not part of the
# uiautomator window dump, so keyboard key centers are measured for 1080x2400@440
# portrait (22021211RC). Re-measure if the layout changes.
param(
  [string]$Serial = "fe4ab855",
  [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/device-qa/editing-tools'),
  [string]$Tag = "editing",
  [string]$BaseUrl = "http://127.0.0.1:8769"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }
$KEY = @{
  textarea = "540 600"
  edit = "872 1494"
  selectAll = "158 1795"
  copy = "413 1795"
  cut = "668 1795"
  paste = "923 1795"
  wordLeft = "158 1975"
  wordRight = "413 1975"
  deleteWord = "668 1975"
  collapse = "923 1975"
  gestureStart = "540 2305"
  gestureLeftEnd = "440 2305"
  gestureRightEnd = "660 2305"
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
function Tap-Key([string]$name) {
  $parts = $KEY[$name].Split(" ")
  Invoke-Adb @("shell", "input", "tap", $parts[0], $parts[1]) | Out-Null
  Start-Sleep -Milliseconds 650
}
function Get-Ui([string]$xmlPath) {
  Invoke-Adb @("shell", "uiautomator", "dump", "--compressed", "/sdcard/bitap_editing_ui.xml") | Out-Null
  Invoke-Adb @("pull", "/sdcard/bitap_editing_ui.xml", $xmlPath) | Out-Null
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
  [pscustomobject]@{
    Text = $text
    Len = [int]$m.Groups[1].Value
    Start = [int]$m.Groups[2].Value
    End = [int]$m.Groups[3].Value
    Value = $value
  }
}
function Save-Step([string]$name) {
  $png = Join-Path $EvidenceDir "$Tag-$name.png"
  Invoke-Adb @("shell", "screencap", "-p", "/sdcard/bitap_editing.png") | Out-Null
  Invoke-Adb @("pull", "/sdcard/bitap_editing.png", $png) | Out-Null
  $doc = Get-Ui (Join-Path $EvidenceDir "$Tag-$name.xml")
  $status = Get-Status $doc
  if ($status) { Write-Host ("STEP {0,-24} {1}" -f $name, $status.Text) }
  [pscustomobject]@{ Doc = $doc; Status = $status }
}
function Focus-Textarea {
  Tap-Key "textarea"
  Start-Sleep -Milliseconds 700
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
function Open-Toolbar {
  # A fresh focus guarantees the transient toolbar starts closed.
  Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
  Start-Sleep -Milliseconds 400
  Focus-Textarea
  Tap-Key "edit"
}
function Invoke-SpaceSlide([string]$from, [string]$to) {
  $startParts = $from.Split(" ")
  $endParts = $to.Split(" ")
  $midX = [int](([int]$startParts[0] + [int]$endParts[0]) / 2)
  $shell = "input motionevent DOWN $($startParts[0]) $($startParts[1]); sleep 0.8; " +
    "input motionevent MOVE $midX $($startParts[1]); " +
    "input motionevent MOVE $($endParts[0]) $($endParts[1]); " +
    "input motionevent UP $($endParts[0]) $($endParts[1])"
  Invoke-Adb @("shell", $shell) | Out-Null
  Start-Sleep -Milliseconds 800
}

Write-Output "== Bitap editing-tools device test tag=$Tag device=$Serial =="
Invoke-Adb @("shell", "input", "keyevent", "KEYCODE_WAKEUP") | Out-Null
Invoke-Adb @("shell", "wm", "dismiss-keyguard") | Out-Null
Invoke-Adb @("shell", "settings", "put", "system", "accelerometer_rotation", "0") | Out-Null
Invoke-Adb @("shell", "settings", "put", "system", "user_rotation", "0") | Out-Null
$port = ([uri]$BaseUrl).Port
Invoke-Adb @("reverse", "tcp:$port", "tcp:$port") | Out-Null
Invoke-Adb @("shell", "am", "force-stop", "mark.via") | Out-Null
Start-Sleep -Milliseconds 400
Invoke-Adb @("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "$BaseUrl/p0.html", "-n", "mark.via/.Shell") | Out-Null
Start-Sleep -Seconds 3
Focus-Textarea
Clear-Page
Focus-Textarea
Invoke-Adb @("shell", "input", "text", "hello%sworld") | Out-Null
Start-Sleep -Milliseconds 800
$s = Save-Step "01-ready"
Add-Result "ready-text" ($s.Status -and $s.Status.Len -eq 11 -and $s.Status.Value -eq "hello world") ("status=" + $s.Status.Text)

Open-Toolbar
$s = Save-Step "02-tools-open"
Add-Result "toolbar-open" ($null -ne $s.Status) ("status=" + $s.Status.Text)

Tap-Key "selectAll"
$s = Save-Step "03-select-all"
Add-Result "select-all" ($s.Status.Start -eq 0 -and $s.Status.End -eq 11) $s.Status.Text

Tap-Key "copy"
$s = Save-Step "04-copy"
Add-Result "copy-keeps-selection" ($s.Status.Start -eq 0 -and $s.Status.End -eq 11 -and $s.Status.Value -eq "hello world") $s.Status.Text

Clear-Page
Open-Toolbar
Tap-Key "paste"
$s = Save-Step "05-paste"
Add-Result "paste" ($s.Status.Len -eq 11 -and $s.Status.Value -eq "hello world") $s.Status.Text

Tap-Key "selectAll"
Tap-Key "cut"
$s = Save-Step "06-cut"
Add-Result "cut" ($s.Status.Len -eq 0 -and $s.Status.Start -eq 0) $s.Status.Text

Tap-Key "paste"
$s = Save-Step "07-paste-after-cut"
Add-Result "paste-after-cut" ($s.Status.Len -eq 11 -and $s.Status.Value -eq "hello world") $s.Status.Text

Tap-Key "deleteWord"
$s = Save-Step "08-delete-word"
Add-Result "delete-word" ($s.Status.Len -eq 6 -and $s.Status.Value -eq "hello ") $s.Status.Text

Tap-Key "wordLeft"
$s = Save-Step "09-word-left"
Add-Result "word-left" ($s.Status.Start -eq 0 -and $s.Status.End -eq 0) $s.Status.Text

Tap-Key "wordRight"
$s = Save-Step "10-word-right"
Add-Result "word-right" ($s.Status.Start -eq 5 -and $s.Status.End -eq 5) $s.Status.Text

Tap-Key "collapse"
$s = Save-Step "11-tools-closed"
Add-Result "toolbar-close" ($null -ne $s.Status) $s.Status.Text

# Long-press then slide in the blank strip under the spacebar moves the cursor.
Clear-Page
Focus-Textarea
Invoke-Adb @("shell", "input", "text", "hello%sworld") | Out-Null
Start-Sleep -Milliseconds 700
$s = Save-Step "12-gesture-ready"
Add-Result "gesture-ready" ($s.Status.Len -eq 11 -and $s.Status.Start -eq 11) $s.Status.Text
Invoke-SpaceSlide $KEY.gestureStart $KEY.gestureLeftEnd
$s = Save-Step "13-gesture-left"
Add-Result "space-gesture-left" ($s.Status.Start -eq 9 -and $s.Status.End -eq 9) $s.Status.Text
Invoke-SpaceSlide $KEY.gestureStart $KEY.gestureRightEnd
$s = Save-Step "14-gesture-right"
Add-Result "space-gesture-right" ($s.Status.Start -eq 11 -and $s.Status.End -eq 11) $s.Status.Text

Write-Output ""
Write-Output "== Summary =="
foreach ($r in $results) { Write-Output ("[{0}] {1}: {2}" -f $r.Verdict, $r.Step, $r.Detail) }
$failed = @($results | Where-Object { $_.Verdict -eq "FAIL" }).Count
Write-Output ("TOTAL={0} PASS={1} FAIL={2}" -f $results.Count, ($results.Count - $failed), $failed)
if ($failed -gt 0) { exit 1 }
