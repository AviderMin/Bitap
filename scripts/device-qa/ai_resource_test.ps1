# D02 on-device AI resource measurement for Bitap.
# Scenario: fresh IME process -> keyboard visible idle -> type nihao -> tap AI ->
# sample PSS while generating -> toggle AI off in settings -> sample PSS at 0/5/10/15/20 s.
# Requires: debug build with model imported; adb reverse tcp:8769 for p0.html; portrait 1080x2400.
# Usage: pwsh -NoProfile -File scripts/device-qa/ai_resource_test.ps1 -Tag d02
param(
  [string]$Serial = "fe4ab855",
  [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/device-qa/ai'),
  [string]$Tag = "d02",
  [string]$Query = "nihao",
  [int]$PeakSeconds = 25
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }
$KEY = @{
  q="61 1665"; w="168 1665"; e="275 1665"; r="382 1665"; t="488 1665"; y="594 1665"; u="700 1665"; i="806 1665"; o="912 1665"; p="1018 1665";
  a="106 1841"; s="215 1841"; d="324 1841"; f="433 1841"; g="541 1841"; h="650 1841"; j="757 1841"; k="866 1841"; l="974 1841";
  z="222 2017"; x="328 2017"; c="434 2017"; v="540 2017"; b="646 2017"; n="752 2017"; m="858 2017";
  shift="88 2017"; backspace="991 2017";
  comma="293 2196"; space="590 2196"; period="887 2196"; enter="1004 2196";
  first_candidate="85 1494"; ai="871 1494"; gear="1004 1494"
}
function Invoke-Taps([string[]]$keys, [int]$gapMs = 110) {
  $parts = @(); foreach ($k in $keys) { if (-not $KEY.ContainsKey($k)) { throw "no key $k" }; $parts += "input tap $($KEY[$k])" }
  adb -s $Serial shell ($parts -join ("; sleep " + ($gapMs / 1000.0).ToString("0.00") + "; ")) | Out-Null
}
function Type-String([string]$text) {
  $keys = @(); foreach ($ch in $text.ToCharArray()) { $keys += [string]$ch }
  Invoke-Taps $keys
}
function Sampled-Pss([string]$name) {
  $file = Join-Path $EvidenceDir "$Tag-$name.txt"
  $raw = adb -s $Serial shell dumpsys meminfo com.bitap.keyboard
  $raw | Set-Content -Path $file -Encoding UTF8
  $m = ($raw | Select-String -Pattern 'TOTAL PSS:\s*(\d+)').Matches
  $pss = if ($m.Count -gt 0) { [int]$m[0].Groups[1].Value } else { -1 }
  $nm = ($raw | Select-String -Pattern 'Native Heap\s+(\d+)').Matches
  $native = if ($nm.Count -gt 0) { [int]$nm[0].Groups[1].Value } else { -1 }
  $jm = ($raw | Select-String -Pattern 'Java Heap:\s*(\d+)').Matches
  $java = if ($jm.Count -gt 0) { [int]$jm[0].Groups[1].Value } else { -1 }
  $ts = Get-Date -Format "HH:mm:ss.fff"
  Write-Host ("PSS {0,-22} total={1,9} KB native={2,8} java={3,8}  ({4})" -f $name, $pss, $native, $java, $ts)
  [pscustomobject]@{ Name=$name; Time=$ts; TotalPssKB=$pss; NativeKB=$native; JavaKB=$java; File=$file }
}
function Get-Ui([string]$xmlPath) {
  adb -s $Serial shell uiautomator dump /sdcard/bitap_ai_ui.xml | Out-Null
  adb -s $Serial pull /sdcard/bitap_ai_ui.xml $xmlPath 2>$null | Out-Null
  [xml](Get-Content -Path $xmlPath -Raw -Encoding UTF8)
}
function Find-AiSwitch {
  param([string]$prefix)
  for ($i = 0; $i -lt 6; $i++) {
    $doc = Get-Ui (Join-Path $EvidenceDir "$Tag-$prefix-$i.xml")
    $nodes = @($doc.SelectNodes("//node"))
    $label = $nodes | Where-Object { $_.GetAttribute("text") -eq "本地 AI 词库" } | Select-Object -First 1
    if (-not $label) {
      adb -s $Serial shell input swipe 540 2000 540 700 300 | Out-Null
      Start-Sleep -Milliseconds 700
      continue
    }
    $idx = [Array]::IndexOf($nodes, $label)
    for ($j = [Math]::Max(0, $idx); $j -le [Math]::Min($idx + 8, $nodes.Count - 1); $j++) {
      if ($nodes[$j].GetAttribute("checkable") -eq "true") {
        return [pscustomobject]@{ Node=$nodes[$j]; Checked=$nodes[$j].GetAttribute("checked"); Bounds=$nodes[$j].GetAttribute("bounds"); Xml=$doc }
      }
    }
  }
  return $null
}
function Tap-Bounds([string]$bounds) {
  $nums = [regex]::Matches($bounds,'\d+') | ForEach-Object { [int]$_.Value }
  adb -s $Serial shell input tap ([int](($nums[0]+$nums[2])/2)) ([int](($nums[1]+$nums[3])/2)) | Out-Null
}
function Screen([string]$name) {
  $png = Join-Path $EvidenceDir "$Tag-$name.png"
  adb -s $Serial shell screencap -p /sdcard/bitap_ai.png | Out-Null
  adb -s $Serial pull /sdcard/bitap_ai.png $png 2>$null | Out-Null
  Write-Host "screenshot: $png"
}

$records = @()
Write-Output "== D02 AI resource test tag=$Tag device=$Serial $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') =="
# fixed environment: manual brightness 20, portrait, Bitap default
adb -s $Serial shell settings put system screen_brightness_mode 1 | Out-Null
adb -s $Serial shell settings put system screen_brightness 20 | Out-Null
adb -s $Serial shell settings put system accelerometer_rotation 0 | Out-Null
adb -s $Serial shell settings put system user_rotation 0 | Out-Null
adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
adb -s $Serial shell wm dismiss-keyguard | Out-Null
$battery = adb -s $Serial shell dumpsys battery | Select-String -Pattern 'temperature:'
Write-Output "battery: $battery"

# Fresh IME process: force-stop, wait for system re-bind, then open test page and keyboard
adb -s $Serial shell am force-stop com.bitap.keyboard | Out-Null
Start-Sleep -Seconds 5
adb -s $Serial shell ime set com.bitap.keyboard/.ime.BitapImeService | Out-Null
Start-Sleep -Seconds 2
adb -s $Serial shell am force-stop mark.via | Out-Null
Start-Sleep -Milliseconds 500
adb -s $Serial shell am start -a android.intent.action.VIEW -d "http://127.0.0.1:8769/p0.html" -n mark.via/.Shell | Out-Null
Start-Sleep -Seconds 3
adb -s $Serial shell input tap 540 600 | Out-Null
Start-Sleep -Seconds 3
Screen "00-keyboard"
$records += Sampled-Pss "01-before-generation"
Type-String $Query
Start-Sleep -Milliseconds 1600
Screen "01-query-candidates"
$records += Sampled-Pss "02-before-ai-tap"

# Tap AI and sample memory while generating
adb -s $Serial shell input tap 871 1494 | Out-Null
$peak = -1; $peakAt = ""
for ($i = 1; $i -le $PeakSeconds; $i++) {
  Start-Sleep -Seconds 1
  $r = Sampled-Pss ("03-ai-gen-{0:d2}s" -f $i)
  $records += $r
  if ($r.TotalPssKB -gt $peak) { $peak = $r.TotalPssKB; $peakAt = $r.Time }
  if ($i -in @(2,4,6)) { Screen ("03-ai-gen-{0:d2}s" -f $i) }
}
Write-Output ("PEAK TOTAL PSS = {0} KB at {1}" -f $peak, $peakAt)
Screen "04-ai-gen-end"

# Open settings and read runtime status / AI switch state before toggle
adb -s $Serial shell input keyevent KEYCODE_HOME | Out-Null
Start-Sleep -Milliseconds 400
adb -s $Serial shell am start -n com.bitap.keyboard/.MainActivity | Out-Null
Start-Sleep -Seconds 2
$sw = Find-AiSwitch "05-settings"
if (-not $sw) { throw "AI switch not found in settings" }
Screen "05-settings"
$records += Sampled-Pss "04-before-ai-off"
Write-Output ("AI switch before toggle: checked={0} bounds={1}" -f $sw.Checked, $sw.Bounds)
$statusNodes = $sw.Xml.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -match '已安装|就绪|思考中|加载中|异常|未安装' }
$runtimeLabel = ($statusNodes | ForEach-Object { $_.GetAttribute("text") } | Select-Object -Unique) -join "|"
Write-Output ("runtime UI labels before toggle: {0}" -f $runtimeLabel)

# Toggle AI off and sample at 0/5/10/15/20 s after the tap
Tap-Bounds $sw.Bounds
$t0 = Get-Date
$records += Sampled-Pss "05-ai-off-0s"
foreach ($sec in @(5,10,15,20)) {
  $elapsed = ((Get-Date) - $t0).TotalSeconds
  $wait = $sec - $elapsed
  if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]($wait * 1000)) }
  $records += Sampled-Pss ("06-ai-off-{0:d2}s" -f $sec)
}
$docOff = Get-Ui (Join-Path $EvidenceDir "$Tag-07-after-ai-off.xml")
$offLabel = ($docOff.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -match '已安装|就绪|思考中|加载中|异常|未安装' } | ForEach-Object { $_.GetAttribute("text") } | Select-Object -Unique) -join "|"
Screen "07-after-ai-off"
Write-Output ("runtime UI labels after toggle: {0}" -f $offLabel)

$records | Format-Table -AutoSize | Out-String | Write-Output
$csv = Join-Path $EvidenceDir "$Tag-pss.csv"
$records | Export-Csv -Path $csv -NoTypeInformation -Encoding UTF8
Write-Output "csv: $csv"
$base = ($records | Where-Object Name -eq '01-before-generation').TotalPssKB
$off0 = ($records | Where-Object Name -eq '05-ai-off-0s').TotalPssKB
$off5 = ($records | Where-Object Name -eq '06-ai-off-05s').TotalPssKB
$off10 = ($records | Where-Object Name -eq '06-ai-off-10s').TotalPssKB
$off20 = ($records | Where-Object Name -eq '06-ai-off-20s').TotalPssKB
Write-Output ("SUMMARY baseline={0}KB peak={1}KB off0={2}KB off5={3}KB off10={4}KB off20={5}KB" -f $base, $peak, $off0, $off5, $off10, $off20)
