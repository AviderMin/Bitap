#requires -Version 5.1
<#
.SYNOPSIS
    Regression check for the apksigner verification gate in scripts/build-release.ps1.

.DESCRIPTION
    Builds a throwaway fake repo and Android SDK so the real build-release script
    can be exercised without Gradle or a release keystore:
      - fake gradlew.bat produces a minimal zip as the unsigned APK
      - zipalign.exe is copied from the real Android SDK build-tools
      - fake apksigner.bat signs by copying, and verifies with a configurable exit code

    Case 1 (verify exits 1): the script must fail, must not create
    dist/SHA256SUMS.txt or the release APK, and must not print the hash/size/commit
    summary.
    Case 2 (verify exits 0): the script must succeed, publish the APK and write
    dist/SHA256SUMS.txt with the correct hash, printing only the first 4 cert lines.

.PARAMETER BuildToolsVersion
    Android build-tools version used to source zipalign.exe. Defaults to 36.0.0.
#>
[CmdletBinding()]
param(
    [string]$BuildToolsVersion = '36.0.0'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$buildRelease = Join-Path $PSScriptRoot 'build-release.ps1'
$workRoot = Join-Path $env:TEMP ('bitap-build-release-test-' + [guid]::NewGuid().ToString('N'))
$savedVerifyExit = $env:FAKE_APKSIGNER_VERIFY_EXIT

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw ('ASSERT FAILED: ' + $Message) }
}

function Resolve-RealAndroidSdk {
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

function New-ZipFixture {
    param([string]$Path)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($Path, 'Create')
    try {
        $entry = $zip.CreateEntry('AndroidManifest.xml')
        $writer = New-Object System.IO.StreamWriter($entry.Open())
        try { $writer.Write('<manifest package="com.example.bitap.fixture" />') }
        finally { $writer.Dispose() }
    }
    finally {
        $zip.Dispose()
    }
}

function Invoke-BuildReleaseCase {
    param(
        [string]$Name,
        [string]$VerifyExit
    )

    $oldSdkRoot = $env:ANDROID_SDK_ROOT
    $oldAndroidHome = $env:ANDROID_HOME
    $oldTemp = $env:TEMP
    $oldTmp = $env:TMP
    $caseTemp = Join-Path $workRoot ('temp-' + $Name)
    New-Item -ItemType Directory -Force -Path $caseTemp | Out-Null
    $env:FAKE_APKSIGNER_VERIFY_EXIT = $VerifyExit
    $env:ANDROID_SDK_ROOT = $null
    $env:ANDROID_HOME = $null
    $env:TEMP = $caseTemp
    $env:TMP = $caseTemp
    try {
        $stdoutPath = Join-Path $workRoot ($Name + '.stdout.log')
        $stderrPath = Join-Path $workRoot ($Name + '.stderr.log')
        $powershellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $argumentList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $scriptPath + '"'))
        $process = Start-Process -FilePath $powershellExe -ArgumentList $argumentList -WorkingDirectory $fakeRepo -Wait -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $stdout = if (Test-Path $stdoutPath) { Get-Content -Raw $stdoutPath } else { '' }
        $stderr = if (Test-Path $stderrPath) { Get-Content -Raw $stderrPath } else { '' }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StdOut   = $stdout
            StdErr   = $stderr
            Log      = $stdout + $stderr
        }
    }
    finally {
        $env:ANDROID_SDK_ROOT = $oldSdkRoot
        $env:ANDROID_HOME = $oldAndroidHome
        $env:TEMP = $oldTemp
        $env:TMP = $oldTmp
    }
}

try {
    if (-not (Test-Path $buildRelease)) { throw "build-release.ps1 not found: $buildRelease" }
    $realSdk = Resolve-RealAndroidSdk
    if (-not $realSdk) { throw 'Android SDK not found; cannot source zipalign.exe for the harness.' }
    $realBuildTools = Join-Path $realSdk "build-tools\$BuildToolsVersion"
    $realZipalign = Join-Path $realBuildTools 'zipalign.exe'
    if (-not (Test-Path $realZipalign)) { throw "zipalign.exe not found: $realZipalign" }

    $fakeRepo = Join-Path $workRoot 'repo'
    $fakeSdk = Join-Path $workRoot 'sdk'
    $fakeTools = Join-Path $fakeSdk "build-tools\$BuildToolsVersion"
    $scriptPath = Join-Path $fakeRepo 'scripts\build-release.ps1'
    New-Item -ItemType Directory -Force -Path (Join-Path $fakeRepo 'scripts'), (Join-Path $fakeRepo 'app\build\outputs\apk\release'), (Join-Path $fakeRepo 'fixture'), $fakeTools | Out-Null

    Copy-Item -LiteralPath $buildRelease -Destination $scriptPath -Force
    New-ZipFixture -Path (Join-Path $fakeRepo 'fixture\unsigned.apk')

    $gradlewBat = @'
@echo off
setlocal
if not exist "app\build\outputs\apk\release" mkdir "app\build\outputs\apk\release"
copy /Y "fixture\unsigned.apk" "app\build\outputs\apk\release\app-release-unsigned.apk" >nul
if errorlevel 1 exit /b 1
exit /b 0
'@
    Set-Content -Path (Join-Path $fakeRepo 'gradlew.bat') -Value $gradlewBat -Encoding ASCII

    Copy-Item -LiteralPath $realZipalign -Destination (Join-Path $fakeTools 'zipalign.exe') -Force
    $pthread = Join-Path $realBuildTools 'libwinpthread-1.dll'
    if (Test-Path $pthread) {
        Copy-Item -LiteralPath $pthread -Destination (Join-Path $fakeTools 'libwinpthread-1.dll') -Force
    }

    $fakeApksigner = @'
$Arguments = @($args)
$action = $Arguments[0]
if ($action -eq 'sign') {
    $outIndex = [array]::IndexOf($Arguments, '--out')
    if ($outIndex -lt 0 -or ($outIndex + 1) -ge $Arguments.Count) {
        [Console]::Error.WriteLine('fake apksigner: missing --out')
        exit 2
    }
    $output = $Arguments[$outIndex + 1]
    $input = $Arguments[$Arguments.Count - 1]
    Copy-Item -LiteralPath $input -Destination $output -Force
    Set-Content -Path ($output + '.idsig') -Value 'fake idsig sidecar' -Encoding ASCII
    exit 0
}
if ($action -eq 'verify') {
    Write-Output 'Signer #1 certificate DN: CN=Android Debug,O=Android,C=US'
    Write-Output 'Signer #1 certificate SHA-256 digest: FIXTURE-SHA256'
    Write-Output 'Signer #1 certificate SHA-1 digest: FIXTURE-SHA1'
    Write-Output 'Signer #1 key algorithm: RSA'
    Write-Output 'Signer #1 key size (bits): 2048'
    [Console]::Error.WriteLine('fake apksigner verify stderr: simulated verification failure detail')
    $exitCode = 0
    if ($env:FAKE_APKSIGNER_VERIFY_EXIT) { $exitCode = [int]$env:FAKE_APKSIGNER_VERIFY_EXIT }
    exit $exitCode
}
[Console]::Error.WriteLine('fake apksigner: unsupported command ' + ($Arguments -join ' '))
exit 2
'@
    Set-Content -Path (Join-Path $fakeTools 'fake-apksigner.ps1') -Value $fakeApksigner -Encoding UTF8

    $apksignerBat = @'
@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-apksigner.ps1" %*
exit /b %ERRORLEVEL%
'@
    Set-Content -Path (Join-Path $fakeTools 'apksigner.bat') -Value $apksignerBat -Encoding ASCII

    $escapedSdk = $fakeSdk -replace '\\', '\\' -replace ':', '\:'
    Set-Content -Path (Join-Path $fakeRepo 'local.properties') -Value ('sdk.dir=' + $escapedSdk) -Encoding ASCII

    Push-Location $fakeRepo
    try {
        git init --quiet
        git config user.email 'build-release-test@example.invalid'
        git config user.name 'Build Release Test'
        git add -A
        git -c commit.gpgsign=false commit --quiet -m 'test fixture'
    }
    finally {
        Pop-Location
    }

    $failure = Invoke-BuildReleaseCase -Name 'failure' -VerifyExit '1'
    Assert-True ($failure.ExitCode -ne 0) ('failure case should exit non-zero, got ' + $failure.ExitCode)
    Assert-True ($failure.Log -match 'apksigner verify failed') 'failure output should mention apksigner verify failed'
    Assert-True ($failure.Log -match 'simulated verification failure detail') 'failure output should include captured apksigner stderr'
    Assert-True (-not (Test-Path (Join-Path $fakeRepo 'dist\SHA256SUMS.txt'))) 'dist/SHA256SUMS.txt must not be written when verification fails'
    Assert-True (-not (Test-Path (Join-Path $fakeRepo 'dist\bitap-0.1.0-demo.apk'))) 'release APK must not be published when verification fails'
    Assert-True (-not $failure.Log.Contains('APK:    ')) 'failure output must not print the APK summary'
    Assert-True (-not $failure.Log.Contains('Size:   ')) 'failure output must not print the APK size'
    $failureTemps = @(Get-ChildItem -Path (Join-Path $workRoot 'temp-failure') -Filter 'bitap-release-*' -ErrorAction SilentlyContinue)
    Assert-True ($failureTemps.Count -eq 0) ('failure case should clean temporary APKs: ' + (($failureTemps | ForEach-Object { $_.Name }) -join ', '))

    $success = Invoke-BuildReleaseCase -Name 'success' -VerifyExit '0'
    Assert-True ($success.ExitCode -eq 0) ('success case should exit 0, got ' + $success.ExitCode + '; log: ' + $success.Log)
    $finalApk = Join-Path $fakeRepo 'dist\bitap-0.1.0-demo.apk'
    $sumsPath = Join-Path $fakeRepo 'dist\SHA256SUMS.txt'
    Assert-True (Test-Path $finalApk) 'release APK should be published after verification succeeds'
    Assert-True (Test-Path $sumsPath) 'dist/SHA256SUMS.txt should be written after verification succeeds'
    $expectedHash = (Get-FileHash -Algorithm SHA256 $finalApk).Hash
    $sumsText = (Get-Content -Raw $sumsPath).Trim()
    Assert-True ($sumsText -eq ($expectedHash + '  bitap-0.1.0-demo.apk')) ('unexpected SHA256SUMS content: ' + $sumsText)
    Assert-True ($success.Log.Contains('Signer #1 certificate DN')) 'success output should print apksigner cert lines'
    Assert-True ($success.Log.Contains('Signer #1 key algorithm')) 'success output should print four cert lines'
    Assert-True (-not $success.Log.Contains('Signer #1 key size')) 'success output should stop after four cert lines'
    Assert-True (-not $success.Log.Contains('simulated verification failure detail')) 'success output should not include verify stderr'
    Assert-True ($success.Log.Contains('SHA256: ' + $expectedHash)) 'success output should print the APK SHA-256'
    Assert-True ($success.Log.Contains('Size:   ' + (Get-Item $finalApk).Length + ' bytes')) 'success output should print the APK size'
    $successTemps = @(Get-ChildItem -Path (Join-Path $workRoot 'temp-success') -Filter 'bitap-release-*' -ErrorAction SilentlyContinue)
    Assert-True ($successTemps.Count -eq 0) ('success case should clean temporary APKs and idsig sidecars: ' + (($successTemps | ForEach-Object { $_.Name }) -join ', '))

    Write-Host ''
    Write-Host 'RESULT: PASS - build-release signature verification gate is enforced.' -ForegroundColor Green
    exit 0
}
catch {
    Write-Host ''
    Write-Host ('RESULT: FAIL - ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
finally {
    $env:FAKE_APKSIGNER_VERIFY_EXIT = $savedVerifyExit
    if (Test-Path $workRoot) { Remove-Item -LiteralPath $workRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
