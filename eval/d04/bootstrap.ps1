#requires -Version 5.1
[CmdletBinding()]
param([string]$WorkDir = '.tmp/d04/bootstrap')
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if (-not [IO.Path]::IsPathRooted($WorkDir)) { $WorkDir = Join-Path $repoRoot $WorkDir }
$cacheRoot = [IO.Path]::GetFullPath($WorkDir)
New-Item -ItemType Directory -Force $cacheRoot | Out-Null
$archivePath = Join-Path $cacheRoot 'librime-msvc.7z'
$url = 'https://github.com/rime/librime/releases/download/1.17.0/rime-33e7814-Windows-msvc-x64.7z'
$expectedHash = '7478C7CAA4FF6B37DE86DABA1F7CE4A994A4F5BA24872A820FB2B3A9B01FED15'
if (-not (Test-Path -LiteralPath $archivePath)) { Invoke-WebRequest $url -OutFile $archivePath -UseBasicParsing }
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'librime archive hash mismatch; no extraction performed'
}
$nativeDir = Join-Path $cacheRoot 'native'
New-Item -ItemType Directory -Force $nativeDir | Out-Null
& tar -xf $archivePath -C $nativeDir
if ($LASTEXITCODE -ne 0) { throw 'tar extraction failed (Windows tar with 7z support required)' }
$sourceDir = Join-Path $cacheRoot 'pinyin-simp'
$sourceCommit = '0c6861ef7420ee780270ca6d993d18d4101049d0'
if (-not (Test-Path -LiteralPath $sourceDir)) {
    & git clone --quiet https://github.com/rime/rime-pinyin-simp.git $sourceDir
    if ($LASTEXITCODE -ne 0) { throw 'dictionary clone failed' }
    & git -C $sourceDir checkout --quiet --detach $sourceCommit
    if ($LASTEXITCODE -ne 0) { throw 'dictionary checkout failed' }
}
$actualCommit = & git -C $sourceDir rev-parse HEAD
if ($actualCommit -ne $sourceCommit) { throw 'dictionary revision mismatch' }
& git -C $sourceDir diff --quiet HEAD -- pinyin_simp.dict.yaml LICENSE AUTHORS
if ($LASTEXITCODE -ne 0) { throw 'dictionary/license source has local modifications' }
$sharedDir = Join-Path $cacheRoot 'shared'
New-Item -ItemType Directory -Force $sharedDir | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'bitap_d04.schema.yaml'),(Join-Path $PSScriptRoot 'default.yaml'),(Join-Path $sourceDir 'pinyin_simp.dict.yaml') -Destination $sharedDir
$dllPath = Join-Path $nativeDir 'dist/lib/rime.dll'
if ((Get-FileHash -LiteralPath $dllPath -Algorithm SHA256).Hash -ne '86B4C7357D4C6D293CE5589B234D8859CA2AC30923A03BEDFA3926EEAF97FB0B') {
    throw 'extracted DLL hash mismatch'
}
Write-Host "dll=$dllPath"
Write-Host "shared=$sharedDir"
Write-Host 'Bootstrap only downloads experiment dependencies; nothing is added to the APK.'
