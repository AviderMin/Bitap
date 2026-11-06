#requires -Version 5.1
<#
.SYNOPSIS
    Bitap 0.1 QA gate: unit tests -> debug APK -> network permission check.

.DESCRIPTION
    Runs the three release gates from docs/06-quality-bar.md and docs/05-privacy-security.md:
      1. .\gradlew.bat :app:testDebugUnitTest
      2. .\gradlew.bat :app:assembleDebug
      3. build-tools 36.0.0 aapt dump permissions on app-debug.apk
    Any failing step exits with code 1. Warnings are printed but do not fail the gate.

.PARAMETER BuildToolsVersion
    Android build-tools version used for aapt. Defaults to 36.0.0.

.EXAMPLE
    .\scripts\verify.ps1
#>
[CmdletBinding()]
param(
    [string]$BuildToolsVersion = '36.0.0'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'

function Write-Section {
    param([string]$Title)
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor Cyan
    Write-Host $Title -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor Cyan
}

function Invoke-GradleStep {
    param([string]$Label, [string[]]$Arguments)
    Write-Section $Label
    & $script:gradlew @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw ("$Label failed with exit code $LASTEXITCODE")
    }
    Write-Host ("OK: $Label") -ForegroundColor Green
}

function Resolve-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'build-tools'))) {
            return $candidate
        }
    }
    $localProperties = Join-Path $repoRoot 'local.properties'
    if (Test-Path $localProperties) {
        $line = Get-Content $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1
        if ($line) {
            $value = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
            $value = $value -replace '\\\\', '\' -replace '\\:', ':'
            if (Test-Path $value) {
                return $value
            }
        }
    }
    $fallback = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $fallback) {
        return $fallback
    }
    return $null
}

function Resolve-Aapt {
    param([string]$SdkRoot, [string]$Version)
    $candidates = New-Object System.Collections.Generic.List[string]
    $buildTools = Join-Path $SdkRoot 'build-tools'
    $candidates.Add((Join-Path $buildTools (Join-Path $Version 'aapt.exe')))
    $candidates.Add((Join-Path $buildTools (Join-Path $Version 'aapt')))
    if (Test-Path $buildTools) {
        $latest = Get-ChildItem $buildTools -Directory |
            Sort-Object { [Version]($_.Name.Split('-')[0]) } -Descending |
            Select-Object -First 1
        if ($latest) {
            $candidates.Add((Join-Path $latest.FullName 'aapt.exe'))
            $candidates.Add((Join-Path $latest.FullName 'aapt'))
        }
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }
    return $null
}

function Get-TestSummary {
    $resultsDir = Join-Path $repoRoot 'app\build\test-results\testDebugUnitTest'
    if (-not (Test-Path $resultsDir)) {
        return 'test results directory not found'
    }
    $total = 0
    $failures = 0
    $errors = 0
    $skipped = 0
    Get-ChildItem $resultsDir -Filter '*.xml' | ForEach-Object {
        [xml]$xml = Get-Content -Raw $_.FullName
        $suite = $xml.testsuite
        $total += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
    }
    return ("tests=$total failures=$failures errors=$errors skipped=$skipped")
}

function Test-NetworkPermissions {
    param([string]$Apk, [string]$Aapt)
    Write-Section 'Privacy check: aapt dump permissions'
    if (-not (Test-Path $Apk)) {
        throw "APK not found: $Apk"
    }
    $raw = & $Aapt dump permissions $Apk 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "aapt dump permissions failed with exit code $LASTEXITCODE"
    }
    $text = ($raw | Out-String)
    Write-Host $text

    $forbidden = @(
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE'
    )
    $violations = @()
    foreach ($permission in $forbidden) {
        if ($text -match [regex]::Escape($permission)) {
            $violations += $permission
        }
    }
    if ($violations.Count -gt 0) {
        throw ('Forbidden network permission(s): ' + ($violations -join ', '))
    }
    Write-Host 'OK: no INTERNET / ACCESS_NETWORK_STATE permission declared.' -ForegroundColor Green
}

Push-Location $repoRoot
try {
    Write-Host "Bitap QA gate - repo: $repoRoot"

    $script:gradlew = Join-Path $repoRoot 'gradlew.bat'
    if (-not (Test-Path $script:gradlew)) {
        $script:gradlew = Join-Path $repoRoot 'gradlew'
    }
    if (-not (Test-Path $script:gradlew)) {
        throw 'Gradle wrapper not found (gradlew.bat / gradlew).'
    }

    Invoke-GradleStep -Label 'Unit tests: :app:testDebugUnitTest' -Arguments @(':app:testDebugUnitTest', '--console=plain')
    Write-Host ('Test results: ' + (Get-TestSummary))

    Invoke-GradleStep -Label 'Build: :app:assembleDebug' -Arguments @(':app:assembleDebug', '--console=plain')

    if (-not (Test-Path $apkPath)) {
        throw "Debug APK not found after assemble: $apkPath"
    }
    $apk = Get-Item $apkPath
    Write-Host ('APK: ' + $apk.FullName)
    Write-Host ('APK size: ' + $apk.Length + ' bytes (' + [math]::Round($apk.Length / 1MB, 2) + ' MB)')

    $sdkRoot = Resolve-AndroidSdk
    if (-not $sdkRoot) {
        throw 'Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties.'
    }
    $aapt = Resolve-Aapt -SdkRoot $sdkRoot -Version $BuildToolsVersion
    if (-not $aapt) {
        throw "aapt not found under $sdkRoot\build-tools (requested $BuildToolsVersion)."
    }
    Write-Host ('aapt: ' + $aapt)

    Test-NetworkPermissions -Apk $apk.FullName -Aapt $aapt

    Write-Section 'RESULT: PASS'
    exit 0
}
catch {
    Write-Host ''
    Write-Host ('RESULT: FAIL - ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
finally {
    Pop-Location
}
