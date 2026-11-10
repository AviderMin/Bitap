# Shared helpers for the debug-only editor host. Coordinates are calibrated for
# 22021211RC / 1080x2400@440 portrait, default keyboard height.
$ErrorActionPreference = 'Stop'
$script:qaResults = @()
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
function Invoke-QaAdb([string[]]$Arguments) {
    $output = & adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' '): $output" }
    $output | Out-String
}
function Tap-Qa([int]$X, [int]$Y, [int]$Delay = 350) {
    Invoke-QaAdb @('shell', 'input', 'tap', "$X", "$Y") | Out-Null
    Start-Sleep -Milliseconds $Delay
}
function Get-QaUi([string]$Name) {
    Invoke-QaAdb @('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/bitap_qa.xml') | Out-Null
    $path = Join-Path $EvidenceDir "$Name.xml"
    Invoke-QaAdb @('pull', '/sdcard/bitap_qa.xml', $path) | Out-Null
    [xml](Get-Content $path -Raw -Encoding UTF8)
}
function Get-QaStatus([string]$Name) {
    $doc = Get-QaUi $Name
    $node = $doc.SelectNodes('//node') | Where-Object { $_.text -like 'len=*' } | Select-Object -First 1
    if (-not $node) { throw "Editor status missing: $Name" }
    $match = [regex]::Match($node.text, '^len=(\d+) sel=(\d+)-(\d+) value=(.*)$')
    if (-not $match.Success) { throw "Invalid editor status: $($node.text)" }
    $value = $match.Groups[4].Value
    if ($script:qaField -eq 'web') { $value = ConvertFrom-Json -InputObject $value }
    [pscustomobject]@{ Value = $value; Start = [int]$match.Groups[2].Value; End = [int]$match.Groups[3].Value; Text = $node.text }
}
function Assert-Qa([string]$Name, [bool]$Pass, [string]$Detail) {
    $verdict = if ($Pass) { 'PASS' } else { 'FAIL' }
    $script:qaResults += [pscustomobject]@{ Name = $Name; Verdict = $verdict; Detail = $Detail }
    Write-Output "[$verdict] ${Name}: $Detail"
}
function Assert-QaEditor([string]$Name, [string]$Value, [int]$Start, [int]$End = $Start) {
    $status = Get-QaStatus $Name
    Assert-Qa $Name ($status.Value -ceq $Value -and $status.Start -eq $Start -and $status.End -eq $End) $status.Text
}
function Start-QaEditor([string]$Field, [string]$Preset = 'emoji', [int]$Selection = -1) {
    $script:qaField = $Field
    # Clear the activity task, not the package: force-stop would silently switch
    # the default IME to the system keyboard and invalidate coordinate checks.
    $arguments = @('shell', 'am', 'start', '-f', '0x10008000', '-n', 'com.bitap.keyboard/.qa.QaEditorActivity', '--es', 'field', $Field, '--es', 'preset', $Preset)
    if ($Selection -ge 0) { $arguments += @('--ei', 'selectionStart', "$Selection", '--ei', 'selectionEnd', "$Selection") }
    Invoke-QaAdb $arguments | Out-Null
    Start-Sleep -Milliseconds 900
    if ($Field -eq 'web') { Tap-Qa 500 350 600 }
    $ime = Invoke-QaAdb @('shell', 'dumpsys', 'input_method')
    if ($ime -notmatch 'mCurId=com.bitap.keyboard/.ime.BitapImeService' -or $ime -notmatch 'mInputShown=true') {
        throw 'Bitap keyboard is not shown; refusing to inject calibrated coordinates'
    }
}
function Move-QaCursor([int]$Direction) {
    $target = 540 + 50 * $Direction
    Invoke-QaAdb @('shell', "input motionevent DOWN 540 2305; sleep 0.7; input motionevent MOVE $target 2305; sleep 0.15; input motionevent UP $target 2305") | Out-Null
    Start-Sleep -Milliseconds 400
}
function Complete-QaReport {
    $packagePath = ((Invoke-QaAdb @('shell', 'pm', 'path', 'com.bitap.keyboard')) -split "`n" | Select-Object -First 1).Trim() -replace '^package:', ''
    [pscustomobject]@{
        CapturedAt = (Get-Date).ToString('o')
        Serial = $Serial
        Model = (Invoke-QaAdb @('shell', 'getprop', 'ro.product.model')).Trim()
        Android = (Invoke-QaAdb @('shell', 'getprop', 'ro.build.version.release')).Trim()
        Screen = (Invoke-QaAdb @('shell', 'wm', 'size')).Trim()
        Density = (Invoke-QaAdb @('shell', 'wm', 'density')).Trim()
        InstalledApkSha256 = ((Invoke-QaAdb @('shell', 'sha256sum', $packagePath)).Trim() -split '\s+')[0]
    } | ConvertTo-Json | Set-Content (Join-Path $EvidenceDir 'environment.json') -Encoding UTF8
    $script:qaResults | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $EvidenceDir 'results.json') -Encoding UTF8
    $failed = @($script:qaResults | Where-Object Verdict -eq 'FAIL').Count
    Write-Output "TOTAL=$($script:qaResults.Count) PASS=$($script:qaResults.Count - $failed) FAIL=$failed"
    if ($failed -gt 0) { throw "$failed device assertions failed" }
}
