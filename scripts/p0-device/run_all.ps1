# Run all P0 acceptance items on a given final APK (single operator).
# Usage:
#   powershell -File scripts/p0-device/run_all.ps1 -ApkPath dist/bitap-0.1.0-demo.apk -Commit <sha> -EvidenceDir .tmp/p0final/evidence
# Item4 is run separately because the AI cancel affordance must be inspected on the final UI;
# use run_item4.ps1 with -EvidenceDir .tmp/p0final/evidence -Tag p0-final-item4.
param(
  [Parameter(Mandatory=$true)][string]$ApkPath,
  [string]$Commit = "",
  [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/p0final/evidence')
)
$ErrorActionPreference = "Stop"
$serial = if ($env:BITAP_DEVICE) { $env:BITAP_DEVICE } else { "fe4ab855" }
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)  # scripts/p0-device -> repo root
$apk = if ([System.IO.Path]::IsPathRooted($ApkPath)) { $ApkPath } else { Join-Path $repo $ApkPath }
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }
if (-not (Test-Path $EvidenceDir)) { New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null }

$hash = (Get-FileHash -Algorithm SHA256 $apk).Hash
$size = (Get-Item $apk).Length
$commitActual = if ($Commit) { $Commit } else { (git -C $repo rev-parse HEAD).Trim() }
$installedTime = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

Write-Output "== Install final APK =="
adb -s $serial install -r "$apk" | Out-Host
adb -s $serial shell dumpsys package com.bitap.keyboard | Select-String 'versionName|lastUpdateTime' | Write-Output
$deviceApk = (adb -s $serial shell pm path com.bitap.keyboard | Select-String 'package:').ToString().Replace('package:','').Trim()
$localPull = Join-Path $env:TEMP 'bitap-installed-base.apk'
adb -s $serial pull $deviceApk $localPull | Out-Null
$deviceHash = (Get-FileHash -Algorithm SHA256 $localPull).Hash
Write-Output ("APK local SHA256: {0}" -f $hash)
Write-Output ("APK device SHA256: {0}" -f $deviceHash)

& (Join-Path $PSScriptRoot "setup_env.ps1") -Serial $serial | Out-Host

$info = @"
P0 final package info
APK: $apk
APK SHA-256: $hash
Device base.apk SHA-256: $deviceHash
Size: $size bytes
Source commit: $commitActual
Installed at: $installedTime
Device: $serial / 22021211RC / Android 16 / API 36 / 1080x2400 / density 440
"@
Set-Content -Path (Join-Path $EvidenceDir "p0-final-package-info.txt") -Value $info -Encoding UTF8
Write-Output $info

Write-Output "== Item 1 (3 rounds) =="
for ($i=1; $i -le 3; $i++) {
  & (Join-Path $PSScriptRoot "run_item1.ps1") -EvidenceDir $EvidenceDir -Tag "p0-final-item1-r$i"
}
Write-Output "== Item 2 =="
& (Join-Path $PSScriptRoot "run_item2.ps1") -EvidenceDir $EvidenceDir -Tag "p0-final-item2"
Write-Output "== Item 3 =="
& (Join-Path $PSScriptRoot "run_item3.ps1") -EvidenceDir $EvidenceDir -Tag "p0-final-item3"
Write-Output "Done items 1-3. Run item4 separately after checking the cancel affordance."
