# Capture a screenshot to a local path.
param(
  [Parameter(Mandatory=$true)][string]$Out
)
$ErrorActionPreference = "Stop"
$serial = if ($env:BITAP_DEVICE) { $env:BITAP_DEVICE } else { "fe4ab855" }
$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
adb -s $serial shell screencap -p /sdcard/p0_capture.png | Out-Null
adb -s $serial pull /sdcard/p0_capture.png "$Out" 2>$null | Out-Null
Write-Output "screenshot: $Out"
