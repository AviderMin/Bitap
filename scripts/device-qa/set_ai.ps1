# Toggle the "本地 AI 词库" switch in Bitap settings through the real UI and verify the preference.
# Usage: pwsh -NoProfile -File scripts/device-qa/set_ai.ps1 -Enabled $true|$false [-Serial fe4ab855]
param(
  [Parameter(Mandatory=$true)][bool]$Enabled,
  [string]$Serial = "fe4ab855",
  [string]$EvidenceDir = "D:\Projects\Github\Bitap\.tmp\device-qa"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }
function Get-Ui([string]$xmlPath) {
  adb -s $Serial shell uiautomator dump /sdcard/bitap_setai.xml | Out-Null
  adb -s $Serial pull /sdcard/bitap_setai.xml $xmlPath 2>$null | Out-Null
  [xml](Get-Content -Path $xmlPath -Raw -Encoding UTF8)
}
adb -s $Serial shell input keyevent KEYCODE_HOME | Out-Null
Start-Sleep -Milliseconds 400
adb -s $Serial shell am start -n com.bitap.keyboard/.MainActivity | Out-Null
Start-Sleep -Seconds 2
$found = $null
# Scroll to the top first: the activity may restore a previous scroll position.
for ($t = 0; $t -lt 4; $t++) {
  adb -s $Serial shell input swipe 540 700 540 2200 250 | Out-Null
  Start-Sleep -Milliseconds 400
}
for ($i = 0; $i -lt 8; $i++) {
  $doc = Get-Ui (Join-Path $EvidenceDir "set-ai-settings-$i.xml")
  $nodes = @($doc.SelectNodes("//node"))
  $label = $nodes | Where-Object { $_.GetAttribute("text") -eq "本地 AI 词库" } | Select-Object -First 1
  if ($label) {
    $idx = [Array]::IndexOf($nodes, $label)
    for ($j = [Math]::Max(0, $idx); $j -le [Math]::Min($idx + 8, $nodes.Count - 1); $j++) {
      if ($nodes[$j].GetAttribute("checkable") -eq "true") { $found = $nodes[$j]; break }
    }
    if ($found) { break }
  }
  adb -s $Serial shell input swipe 540 2000 540 700 300 | Out-Null
  Start-Sleep -Milliseconds 700
}
if (-not $found) { throw "AI switch not found" }
$current = $found.GetAttribute("checked")
$want = if ($Enabled) { "true" } else { "false" }
Write-Output ("AI switch current=$current want=$want bounds=" + $found.GetAttribute("bounds"))
if ($current -ne $want) {
  $b = $found.GetAttribute("bounds"); $nums = [regex]::Matches($b,'\d+') | ForEach-Object { [int]$_.Value }
  adb -s $Serial shell input tap ([int](($nums[0]+$nums[2])/2)) ([int](($nums[1]+$nums[3])/2)) | Out-Null
  Start-Sleep -Seconds 2
}
adb -s $Serial shell run-as com.bitap.keyboard cat shared_prefs/bitap_settings.xml
