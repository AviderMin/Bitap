#requires -Version 5.1
<#
.SYNOPSIS
    Bitap Phase-1 gate: baseline/regression unit tests -> lint -> debug APK -> corpus + privacy checks.

.DESCRIPTION
    Implements the Phase-1 (D00-D03) evidence gate from
    docs/PROJECT_REVIEW_AND_DEVELOPMENT_PLAN_2026-11-08.md section 10/14:
      1. .\gradlew.bat :app:testDebugUnitTest --rerun-tasks (never cached)
      2. .\gradlew.bat :app:lintDebug
      3. .\gradlew.bat :app:assembleDebug
      4. python eval/check_corpus.py + eval/check_no_leak.py + eval/v2/check_v2_corpus.py + eval/v3/check_v3_corpus.py
      5. aapt permission check: no INTERNET / ACCESS_NETWORK_STATE
    Any failing step exits with code 1. The script writes no product data.

.PARAMETER BuildToolsVersion
    Android build-tools version used for aapt. Defaults to 36.0.0.

.EXAMPLE
    .\scripts\verify-phase1.ps1
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

function Invoke-Step {
    param([string]$Label, [scriptblock]$Body)
    Write-Section $Label
    & $Body
    if ($LASTEXITCODE -ne 0) {
        throw ("$Label failed with exit code $LASTEXITCODE")
    }
    Write-Host ("OK: $Label") -ForegroundColor Green
}

function Resolve-AndroidSdk {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'build-tools'))) { return $candidate }
    }
    $localProperties = Join-Path $repoRoot 'local.properties'
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

function Resolve-Aapt {
    param([string]$SdkRoot, [string]$Version)
    $buildTools = Join-Path $SdkRoot 'build-tools'
    foreach ($candidate in @(
        (Join-Path $buildTools (Join-Path $Version 'aapt.exe')),
        (Join-Path $buildTools (Join-Path $Version 'aapt'))
    )) {
        if (Test-Path $candidate) { return $candidate }
    }
    if (Test-Path $buildTools) {
        $latest = Get-ChildItem $buildTools -Directory |
            Sort-Object { [Version]($_.Name.Split('-')[0]) } -Descending |
            Select-Object -First 1
        if ($latest) {
            foreach ($name in @('aapt.exe', 'aapt')) {
                $candidate = Join-Path $latest.FullName $name
                if (Test-Path $candidate) { return $candidate }
            }
        }
    }
    return $null
}

function Get-TestSummary {
    $resultsDir = Join-Path $repoRoot 'app\build\test-results\testDebugUnitTest'
    if (-not (Test-Path $resultsDir)) { return 'test results directory not found' }
    $total = 0; $failures = 0; $errors = 0; $skipped = 0
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
    if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
    $raw = & $Aapt dump permissions $Apk 2>&1
    if ($LASTEXITCODE -ne 0) { throw "aapt dump permissions failed with exit code $LASTEXITCODE" }
    $text = ($raw | Out-String)
    Write-Host $text
    $violations = @()
    foreach ($permission in @('android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE')) {
        if ($text -match [regex]::Escape($permission)) { $violations += $permission }
    }
    if ($violations.Count -gt 0) { throw ('Forbidden network permission(s): ' + ($violations -join ', ')) }
    Write-Host 'OK: no INTERNET / ACCESS_NETWORK_STATE permission declared.' -ForegroundColor Green
}

Push-Location $repoRoot
try {
    Write-Host "Bitap Phase-1 gate - repo: $repoRoot"
    $gradlew = Join-Path $repoRoot 'gradlew.bat'
    if (-not (Test-Path $gradlew)) { $gradlew = Join-Path $repoRoot 'gradlew' }
    if (-not (Test-Path $gradlew)) { throw 'Gradle wrapper not found.' }

    Invoke-Step 'Unit tests: :app:testDebugUnitTest --rerun-tasks' {
        & $gradlew ':app:testDebugUnitTest' '--rerun-tasks' '--console=plain'
    }
    Write-Host ('Test results: ' + (Get-TestSummary))

    Invoke-Step 'Lint: :app:lintDebug' {
        & $gradlew ':app:lintDebug' '--console=plain'
    }

    Invoke-Step 'Build: :app:assembleDebug' {
        & $gradlew ':app:assembleDebug' '--console=plain'
    }

    Invoke-Step 'Corpus checks (v1 + v2)' {
        python eval/check_corpus.py
        if ($LASTEXITCODE -ne 0) { throw "check_corpus.py failed" }
        python eval/check_no_leak.py
        if ($LASTEXITCODE -ne 0) { throw "check_no_leak.py failed" }
        python eval/v2/check_v2_corpus.py
        if ($LASTEXITCODE -ne 0) { throw "check_v2_corpus.py failed" }
        python eval/v3/check_v3_corpus.py
        if ($LASTEXITCODE -ne 0) { throw "check_v3_corpus.py failed" }
        $global:LASTEXITCODE = 0
    }

    if (-not (Test-Path $apkPath)) { throw "Debug APK not found: $apkPath" }
    $apk = Get-Item $apkPath
    Write-Host ('APK: ' + $apk.FullName)
    Write-Host ('APK size: ' + $apk.Length + ' bytes')

    $sdkRoot = Resolve-AndroidSdk
    if (-not $sdkRoot) { throw 'Android SDK not found.' }
    $aapt = Resolve-Aapt -SdkRoot $sdkRoot -Version $BuildToolsVersion
    if (-not $aapt) { throw "aapt not found under $sdkRoot\build-tools." }
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
