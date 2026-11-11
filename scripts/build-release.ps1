#requires -Version 5.1
<#
.SYNOPSIS
    Build, zipalign, sign and verify the Bitap demo release APK reproducibly.

.DESCRIPTION
    1. .\gradlew.bat :app:assembleRelease
    2. zipalign the unsigned APK
    3. apksigner sign with the local Android debug keystore (demo policy)
    4. apksigner verify the signed APK; only then publish dist/bitap-0.1.0-demo.apk
       and write dist/SHA256SUMS.txt

    The Android SDK is resolved from ANDROID_SDK_ROOT, ANDROID_HOME,
    local.properties sdk.dir, or %LOCALAPPDATA%\Android\Sdk.
    Any failing step aborts the script, and the SHA-256/size/commit summary is
    printed only after apksigner verify succeeds.
#>
[CmdletBinding()]
param(
    [string]$BuildToolsVersion = '36.0.0',
    [string]$OutputName = 'bitap-0.1.0-demo.apk'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$keystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
$unsigned = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release-unsigned.apk'
$distDir = Join-Path $repoRoot 'dist'
$finalApk = Join-Path $distDir $OutputName

function Resolve-AndroidSdk {
    param([string]$ProjectRoot)

    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'build-tools'))) { return $candidate }
    }
    $localProperties = Join-Path $ProjectRoot 'local.properties'
    if (Test-Path $localProperties) {
        $line = Get-Content $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1
        if ($line) {
            $value = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
            $value = $value -replace '\\\\', '\' -replace '\\:', ':'
            if (Test-Path $value) { return $value }
        }
    }
    $fallback = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $fallback) { return $fallback }
    return $null
}

function Invoke-ApksignerVerify {
    param(
        [string]$ApkSigner,
        [string]$ApkPath
    )

    # Windows PowerShell 5.1 turns redirected native stderr into a terminating
    # error while ErrorActionPreference is Stop; relax it for this single call.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $verifyOutput = & $ApkSigner verify --print-certs $ApkPath 2>&1
        $verifyExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }

    # 2>&1 makes native stderr lines ErrorRecords; keep them separate from stdout
    # so only the certificate lines are printed on success, while failures can
    # report the complete captured output.
    $stdoutText = ((@($verifyOutput | Where-Object { $_ -isnot [System.Management.Automation.ErrorRecord] }) |
        ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    $stderrText = ((@($verifyOutput | Where-Object { $_ -is [System.Management.Automation.ErrorRecord] }) |
        ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    return [pscustomobject]@{
        ExitCode = $verifyExitCode
        StdOut   = $stdoutText
        StdErr   = $stderrText
    }
}

$sdkRoot = Resolve-AndroidSdk -ProjectRoot $repoRoot
if (-not $sdkRoot) {
    throw 'Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME, or configure sdk.dir in local.properties.'
}
$buildTools = Join-Path $sdkRoot "build-tools\$BuildToolsVersion"
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
if (-not (Test-Path $zipalign)) { throw "zipalign not found: $zipalign" }
if (-not (Test-Path $apksigner)) { throw "apksigner not found: $apksigner" }

$tempDir = if ($env:TEMP) { $env:TEMP } else { [System.IO.Path]::GetTempPath() }
$aligned = Join-Path $tempDir ('bitap-release-aligned-' + [guid]::NewGuid().ToString('N') + '.apk')
$signedTemp = Join-Path $tempDir ('bitap-release-signed-' + [guid]::NewGuid().ToString('N') + '.apk')

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
    & $apksigner sign --ks $keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $signedTemp $aligned
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed: $LASTEXITCODE" }
    if (-not (Test-Path $signedTemp)) { throw "signed APK not found: $signedTemp" }

    Write-Host '== verify =='
    $verification = Invoke-ApksignerVerify -ApkSigner $apksigner -ApkPath $signedTemp
    if ($verification.ExitCode -ne 0) {
        $detail = 'stdout:' + [Environment]::NewLine + $verification.StdOut.Trim() +
            [Environment]::NewLine + 'stderr:' + [Environment]::NewLine + $verification.StdErr.Trim()
        throw ('apksigner verify failed (exit code ' + $verification.ExitCode + '):' + [Environment]::NewLine + $detail)
    }
    @([regex]::Split($verification.StdOut, "\r?\n") | Where-Object { $_.Trim() -ne '' }) |
        Select-Object -First 4 | ForEach-Object { Write-Host $_ }

    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    Copy-Item -LiteralPath $signedTemp -Destination $finalApk -Force

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
    foreach ($tempApk in @($aligned, $signedTemp, ($signedTemp + '.idsig'))) {
        if (Test-Path $tempApk) { Remove-Item $tempApk -Force -ErrorAction SilentlyContinue }
    }
}
