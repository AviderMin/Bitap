# Collect the Phase-1 device baseline for Bitap (read-only except local files).
# Usage: .\collect_baseline.ps1 [-OutDir D:\Projects\Github\Bitap\.tmp\device-qa] [-Serial fe4ab855]
param(
  [string]$OutDir = "D:\Projects\Github\Bitap\.tmp\device-qa",
  [string]$Serial = "fe4ab855"
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
function Save-Cmd([string]$name, [string]$cmd) {
  $out = Invoke-Expression $cmd
  $out | Set-Content -Path (Join-Path $OutDir $name) -Encoding UTF8
  Write-Output ("--- {0} ---" -f $name)
  $out | Write-Output
}
Write-Output "== Device baseline $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') =="
$props = @(
  'ro.product.model','ro.product.brand','ro.product.device','ro.product.name',
  'ro.build.version.release','ro.build.version.sdk','ro.build.version.incremental',
  'ro.build.display.id','ro.build.fingerprint','ro.build.type','ro.debuggable',
  'ro.lineage.version','ro.build.date','ro.product.cpu.abi','ro.sf.lcd_density'
)
$lines = foreach ($p in $props) { "{0}={1}" -f $p, (adb -s $Serial shell getprop $p).Trim() }
$lines | Set-Content -Path (Join-Path $OutDir "baseline-props.txt") -Encoding UTF8
$lines | Write-Output
Save-Cmd "baseline-wm-size.txt" "adb -s $Serial shell wm size"
Save-Cmd "baseline-wm-density.txt" "adb -s $Serial shell wm density"
Save-Cmd "baseline-default-ime.txt" "adb -s $Serial shell settings get secure default_input_method"
Save-Cmd "baseline-ime-list.txt" "adb -s $Serial shell ime list -s"
Save-Cmd "baseline-dumpsys-package.txt" "adb -s $Serial shell dumpsys package com.bitap.keyboard"
Write-Output "== APK =="
$apkPath = (adb -s $Serial shell pm path com.bitap.keyboard) -replace '^package:','' 
$apkPath = $apkPath.Trim()
Write-Output ("APK path: {0}" -f $apkPath)
adb -s $Serial shell sha256sum $apkPath | Write-Output
adb -s $Serial shell stat -c %s $apkPath | Write-Output
Write-Output "== PSS (keyboard hidden/natural idle) =="
& (Join-Path $PSScriptRoot "pss.ps1") -OutFile (Join-Path $OutDir "baseline-pss-idle.txt") -Tag "idle" -Serial $Serial
Write-Output "== Battery =="
adb -s $Serial shell dumpsys battery | Select-String -Pattern 'level:|temperature:|status:' | Write-Output
