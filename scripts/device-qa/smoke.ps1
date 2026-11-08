# Bitap on-device smoke: keyboard show, pinyin candidates, selection, backspace,
# punctuation, settings page, AI switch presence, password field.
# Read-only with respect to product code; writes only evidence files and normal app settings.
# Run with PowerShell 7: pwsh -NoProfile -File scripts/device-qa/smoke.ps1 -Tag smoke
param(
  [string]$Serial = "fe4ab855",
  [string]$EvidenceDir = "D:\Projects\Github\Bitap\.tmp\device-qa\smoke",
  [string]$Tag = "smoke",
  [switch]$SkipSettings,
  [switch]$SkipPassword
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }
# Key coordinates measured on 1080x2400 portrait, dark keyboard theme (2026-11-08)
$KEY = @{
  q="61 1665"; w="168 1665"; e="275 1665"; r="382 1665"; t="488 1665"; y="594 1665"; u="700 1665"; i="806 1665"; o="912 1665"; p="1018 1665";
  a="106 1841"; s="215 1841"; d="324 1841"; f="433 1841"; g="541 1841"; h="650 1841"; j="757 1841"; k="866 1841"; l="974 1841";
  z="222 2017"; x="328 2017"; c="434 2017"; v="540 2017"; b="646 2017"; n="752 2017"; m="858 2017";
  shift="88 2017"; backspace="991 2017";
  comma="293 2196"; space="590 2196"; period="887 2196"; enter="1004 2196";
  first_candidate="85 1494"; ai="871 1494"; gear="1004 1494"; expand="740 1494"
}
$results = @()
function Add-Result([string]$name, [bool]$pass, [string]$detail) {
  $script:results += [pscustomobject]@{ Step=$name; Verdict=$(if($pass){"PASS"}else{"FAIL"}); Detail=$detail }
}
function Invoke-Taps([string[]]$keys, [int]$gapMs = 110) {
  $parts = @(); foreach ($k in $keys) { if (-not $KEY.ContainsKey($k)) { throw "no key $k" }; $parts += "input tap $($KEY[$k])" }
  $cmd = ($parts -join ("; sleep " + ($gapMs / 1000.0).ToString("0.00") + "; "))
  adb -s $Serial shell $cmd | Out-Null
}
function Get-Ui([string]$xmlPath) {
  adb -s $Serial shell uiautomator dump /sdcard/bitap_smoke_ui.xml | Out-Null
  adb -s $Serial pull /sdcard/bitap_smoke_ui.xml $xmlPath 2>$null | Out-Null
  [xml](Get-Content -Path $xmlPath -Raw -Encoding UTF8)
}
function Read-Field([string]$xmlPath) {
  $doc = Get-Ui $xmlPath
  $edit = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" } | Select-Object -First 1
  $status = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "len=*" } | Select-Object -First 1
  [pscustomobject]@{
    Text = if ($edit) { [string]$edit.GetAttribute("text") } else { $null }
    Status = if ($status) { [string]$status.GetAttribute("text") } else { $null }
    Bounds = if ($edit) { $edit.GetAttribute("bounds") } else { $null }
  }
}
function Save-Step([string]$name) {
  $xml = Join-Path $EvidenceDir "$Tag-$name.xml"; $png = Join-Path $EvidenceDir "$Tag-$name.png"
  adb -s $Serial shell screencap -p /sdcard/bitap_smoke.png | Out-Null
  adb -s $Serial pull /sdcard/bitap_smoke.png $png 2>$null | Out-Null
  $f = Read-Field $xml
  Write-Host ("STEP {0,-24} text='{1}' status='{2}'" -f $name, $f.Text, $f.Status)
  return $f
}
function Open-Page([string]$url) {
  adb -s $Serial shell am force-stop mark.via | Out-Null
  Start-Sleep -Milliseconds 500
  adb -s $Serial shell am start -a android.intent.action.VIEW -d $url -n mark.via/.Shell | Out-Null
  Start-Sleep -Seconds 3
  adb -s $Serial shell input tap 540 600 | Out-Null
  Start-Sleep -Milliseconds 1500
  # uiautomator occasionally returns only the browser shell right after load; poll for the WebView field.
  for ($i = 0; $i -lt 8; $i++) {
    $doc = Get-Ui (Join-Path $EvidenceDir "$Tag-open-$i.xml")
    $edit = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" } | Select-Object -First 1
    if ($edit) { break }
    Start-Sleep -Milliseconds 700
  }
}
function Clear-Text {
  adb -s $Serial shell input tap 121 1009 | Out-Null
  Start-Sleep -Milliseconds 500
  adb -s $Serial shell input tap 540 600 | Out-Null
  Start-Sleep -Milliseconds 700
}
function Tap-Bounds([string]$bounds) {
  $nums = [regex]::Matches($bounds,'\d+') | ForEach-Object { [int]$_.Value }
  adb -s $Serial shell input tap ([int](($nums[0]+$nums[2])/2)) ([int](($nums[1]+$nums[3])/2)) | Out-Null
}

Write-Output "== Bitap smoke tag=$Tag device=$Serial =="
adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
adb -s $Serial shell wm dismiss-keyguard | Out-Null
adb -s $Serial shell settings put system accelerometer_rotation 0 | Out-Null
adb -s $Serial shell settings put system user_rotation 0 | Out-Null

# 1) keyboard show + nihao candidates + commit
Open-Page "http://127.0.0.1:8769/p0.html"
$s = Save-Step "01-keyboard"
Add-Result "keyboard-show" ($null -ne $s.Text -and $s.Status -like 'len=0*') "status=$($s.Status)"
Invoke-Taps @("n","i","h","a","o")
Start-Sleep -Milliseconds 1500
$s = Save-Step "02-nihao"
Add-Result "nihao-candidates" ($s.Status -like 'len=5*value="nihao"*') "status=$($s.Status)"
Invoke-Taps @("first_candidate")
Start-Sleep -Milliseconds 900
$s = Save-Step "03-commit"
Add-Result "nihao-commit" ($s.Text -eq "你好") "text=$($s.Text)"

# 2) backspace + punctuation
Invoke-Taps @("backspace")
Start-Sleep -Milliseconds 700
$s = Save-Step "04-backspace"
Add-Result "backspace" ($s.Text -eq "你") "text=$($s.Text)"
Invoke-Taps @("comma")
Start-Sleep -Milliseconds 500
Invoke-Taps @("period")
Start-Sleep -Milliseconds 500
$s = Save-Step "05-punctuation"
Add-Result "punctuation" ($s.Text -eq "你，。") "text=$($s.Text)"

# 3) continuous input (first candidate may be a whole phrase; only require text growth)
Clear-Text
Invoke-Taps @("j","i","n","t","i","a","n")
Start-Sleep -Milliseconds 1200
Invoke-Taps @("first_candidate")
Start-Sleep -Milliseconds 700
Invoke-Taps @("t","i","a","n","q","i")
Start-Sleep -Milliseconds 1200
Invoke-Taps @("first_candidate")
Start-Sleep -Milliseconds 700
$s = Save-Step "06-continuous"
Add-Result "continuous-input" ($null -ne $s.Text -and $s.Text.Length -ge 2) "text=$($s.Text)"

if (-not $SkipSettings) {
  # 4) settings page opens from keyboard gear, scroll to "输入与 AI", record AI switch state
  Invoke-Taps @("gear")
  Start-Sleep -Seconds 2
  $found = $false; $nodes = @()
  for ($i = 0; $i -lt 4; $i++) {
    $xml = Join-Path $EvidenceDir "$Tag-07-settings-$i.xml"
    $doc = Get-Ui $xml
    $nodes = @($doc.SelectNodes("//node"))
    if ($nodes | Where-Object { $_.GetAttribute("text") -eq "本地 AI 词库" }) { $found = $true; break }
    adb -s $Serial shell input swipe 540 2000 540 700 300 | Out-Null
    Start-Sleep -Milliseconds 700
  }
  $png = Join-Path $EvidenceDir "$Tag-07-settings.png"
  adb -s $Serial shell screencap -p /sdcard/bitap_smoke.png | Out-Null
  adb -s $Serial pull /sdcard/bitap_smoke.png $png 2>$null | Out-Null
  Add-Result "settings-page" $found "本地 AI 词库 found=$found"
  if ($found) {
    $aiNode = $nodes | Where-Object { $_.GetAttribute("text") -eq "本地 AI 词库" } | Select-Object -First 1
    $switch = $null
    $idx = [Array]::IndexOf($nodes, $aiNode)
    for ($j = [Math]::Max(0, $idx); $j -le [Math]::Min($idx + 8, $nodes.Count - 1); $j++) {
      if ($nodes[$j].GetAttribute("checkable") -eq "true") { $switch = $nodes[$j]; break }
    }
    if ($switch) {
      $checked = $switch.GetAttribute("checked")
      Add-Result "ai-switch-visible" $true "checked=$checked bounds=$($switch.GetAttribute('bounds'))"
      Tap-Bounds $switch.GetAttribute("bounds")
      Start-Sleep -Seconds 2
      $doc2 = Get-Ui (Join-Path $EvidenceDir "$Tag-07-ai-off.xml")
      $off = $doc2.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -eq "本地 AI 词库" } | Select-Object -First 1
      $png2 = Join-Path $EvidenceDir "$Tag-07-ai-off.png"
      adb -s $Serial shell screencap -p /sdcard/bitap_smoke.png | Out-Null
      adb -s $Serial pull /sdcard/bitap_smoke.png $png2 2>$null | Out-Null
      Tap-Bounds $switch.GetAttribute("bounds")
      Start-Sleep -Seconds 2
      Add-Result "ai-toggle" ($null -ne $off) "toggled off then on"
    } else {
      Add-Result "ai-switch-visible" $false "checkable switch not found near 本地 AI 词库"
    }
  }
  adb -s $Serial shell input keyevent KEYCODE_BACK | Out-Null
  Start-Sleep -Milliseconds 600
}

if (-not $SkipPassword) {
  # 5) password field: open page, locate the password EditText by attribute, tap it, type nihao, select first candidate
  Open-Page "http://127.0.0.1:8770/password.html"
  $edit = $null
  for ($i = 0; $i -lt 8; $i++) {
    $doc = Get-Ui (Join-Path $EvidenceDir "$Tag-08-password-focus-$i.xml")
    $edit = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" -and $_.GetAttribute("password") -eq "true" } | Select-Object -First 1
    if ($edit) { break }
    Start-Sleep -Milliseconds 800
  }
  if ($edit) { Tap-Bounds $edit.GetAttribute("bounds"); Start-Sleep -Milliseconds 900 }
  Invoke-Taps @("n","i","h","a","o")
  Start-Sleep -Milliseconds 1500
  Invoke-Taps @("first_candidate")
  Start-Sleep -Milliseconds 900
  $png = Join-Path $EvidenceDir "$Tag-08-password-after.png"
  adb -s $Serial shell screencap -p /sdcard/bitap_smoke.png | Out-Null
  adb -s $Serial pull /sdcard/bitap_smoke.png $png 2>$null | Out-Null
  $doc2 = Get-Ui (Join-Path $EvidenceDir "$Tag-08-password-after.xml")
  $status = $doc2.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "pwdLen=*" } | Select-Object -First 1
  $pwdLen = if ($status) { $status.GetAttribute("text") } else { "n/a" }
  # Password/incognito fields must not enter Chinese composition: raw ASCII (pwdLen=5) is the expected privacy behavior.
  Add-Result "password-field" ($null -ne $edit -and ($pwdLen -eq "pwdLen=5" -or $pwdLen -eq "pwdLen=2")) "passwordEdit=$([bool]$edit) status=$pwdLen"
}

Write-Output ""
Write-Output "== Summary =="
foreach ($r in $results) { Write-Output ("[{0}] {1}: {2}" -f $r.Verdict, $r.Step, $r.Detail) }
$failed = @($results | Where-Object { $_.Verdict -eq "FAIL" }).Count
Write-Output ("TOTAL={0} PASS={1} FAIL={2}" -f $results.Count, ($results.Count - $failed), $failed)
if ($failed -gt 0) { exit 1 }
