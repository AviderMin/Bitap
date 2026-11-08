#requires -Version 5.1
<#
.SYNOPSIS
    Bitap engineering gate: baseline/regression unit tests -> lint -> debug APK -> corpus + privacy checks.

.DESCRIPTION
    Maintained engineering gate described in
    docs/DEVELOPMENT.md and docs/EVALUATION.md:
      1. .\gradlew.bat :app:testDebugUnitTest --rerun-tasks (never cached)
      2. .\gradlew.bat :app:lintDebug
      3. .\gradlew.bat :app:assembleDebug
      4. python eval/check_corpus.py + eval/check_no_leak.py + eval/v2/check_v2_corpus.py + eval/v3/check_v3_corpus.py
      5. aapt permission check: no INTERNET / ACCESS_NETWORK_STATE
    D05 data, Python contracts and documentation references are also checked.
    StrictSources enables strict v3 source/archive verification.
    Any failing step exits with code 1. The script writes no product data.

.PARAMETER BuildToolsVersion
    Android build-tools version used for aapt. Defaults to 36.0.0.

.EXAMPLE
    .\scripts\verify.ps1
#>
[CmdletBinding()]
param(
    [string]$BuildToolsVersion = '36.0.0',
    [switch]$StrictSources
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$apkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\app-debug.apk'
$optInNames = @('PINYIN_SEALED_RUN','PINYIN_V2_VALIDATION','PINYIN_V3_ACCEPTANCE','BITAP_D04_EXPORT')
$savedOptIns = @{}

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
    foreach ($name in $optInNames) {
        $savedOptIns[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $null, 'Process')
    }
    Write-Host "Bitap engineering gate - repo: $repoRoot"
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

    Invoke-Step 'Corpus checks (v1 + v2 + v3 + D05)' {
        python eval/check_corpus.py
        if ($LASTEXITCODE -ne 0) { throw "check_corpus.py failed" }
        python eval/check_no_leak.py
        if ($LASTEXITCODE -ne 0) { throw "check_no_leak.py failed" }
        python eval/v2/check_v2_corpus.py
        if ($LASTEXITCODE -ne 0) { throw "check_v2_corpus.py failed" }
        if ($StrictSources) { python eval/v3/check_v3_corpus.py --strict }
        else { python eval/v3/check_v3_corpus.py }
        if ($LASTEXITCODE -ne 0) { throw "check_v3_corpus.py failed" }
        python eval/d05/dev/check_d05_dev.py
        if ($LASTEXITCODE -ne 0) { throw "D05 corpus check failed" }
        python -m unittest discover -s eval/d04 -p 'test_*.py'
        if ($LASTEXITCODE -ne 0) { throw "D04 scorer contracts failed" }
        python -m unittest discover -s eval/v3 -p 'test_*.py'
        if ($LASTEXITCODE -ne 0) { throw "v3 overlap contracts failed" }
        python scripts/check_repo_links.py
        if ($LASTEXITCODE -ne 0) { throw "documentation references failed" }
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
    foreach ($name in $savedOptIns.Keys) {
        [Environment]::SetEnvironmentVariable($name, $savedOptIns[$name], 'Process')
    }
    Pop-Location
}
