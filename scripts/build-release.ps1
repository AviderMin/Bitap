#requires -Version 5.1
<#
.SYNOPSIS
    Build, zipalign and sign the Bitap demo release APK reproducibly.

.DESCRIPTION
    1. .\gradlew.bat :app:assembleRelease
    2. zipalign the unsigned APK
    3. apksigner sign with the local Android debug keystore (demo policy)
    4. copy to dist/bitap-0.1.0-demo.apk, write dist/SHA256SUMS.txt

    The output APK's SHA-256 and the current git commit are printed so that
    device acceptance records can pin the exact binary.
#>
[CmdletBinding()]
param(
    [string]$BuildToolsVersion = '36.0.0',
    [string]$OutputName = 'bitap-0.1.0-demo.apk'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = 'D:\Applications\Android-SDK'
$buildTools = Join-Path $sdkRoot "build-tools\$BuildToolsVersion"
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
$keystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
$unsigned = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
$distDir = Join-Path $repoRoot 'dist'
$finalApk = Join-Path $distDir $OutputName
$aligned = Join-Path $env:TEMP 'bitap-release-aligned.apk'

Push-Location $repoRoot
try {
    Write-Host '== assembleRelease =='
    & (Join-Path $repoRoot 'gradlew.bat') :app:assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed: $LASTEXITCODE" }
    if (-not (Test-Path $unsigned)) { throw "unsigned APK not found: $unsigned" }

    Write-Host '== zipalign =='
    & $zipalign -p -f 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw "zipalign failed: $LASTEXITCODE" }

    Write-Host '== apksigner sign =='
    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    & $apksigner sign --ks $keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $finalApk $aligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed: $LASTEXITCODE" }

    Write-Host '== verify =='
    & $apksigner verify --print-certs $finalApk | Select-Object -First 4

    $hash = (Get-FileHash -Algorithm SHA256 $finalApk).Hash
    $size = (Get-Item $finalApk).Length
    $commit = (git rev-parse HEAD).Trim()
    $dirty = if ((git status --porcelain | Measure-Object -Line).Lines -gt 0) { 'dirty' } else { 'clean' }
    Set-Content -Path (Join-Path $distDir 'SHA256SUMS.txt') -Value ("$hash  $OutputName") -Encoding ASCII
    Write-Host ''
    Write-Host ("APK:    " + $finalApk)
    Write-Host ("Size:   " + $size + " bytes")
    Write-Host ("SHA256: " + $hash)
    Write-Host ("Commit: " + $commit + " (" + $dirty + ")")
}
finally {
    Pop-Location
    if (Test-Path $aligned) { Remove-Item $aligned -Force -ErrorAction SilentlyContinue }
}
