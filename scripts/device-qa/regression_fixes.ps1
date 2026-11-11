# Device regression checks for the debug-only QaEditorActivity harness:
#   Test 1: the host ends composing without moving the caret; a following space
#           must insert exactly one space instead of duplicating the old composing text.
#   Test 2: an editor that rejects selectAll and exposes only a local ExtractedText
#           window must stay untouched instead of selecting an arbitrary window.
# Coordinates are calibrated for 22021211RC / 1080x2400@440 portrait, default
# keyboard height (same values as smoke.ps1 / editing_tools.ps1).
# Run with PowerShell 7:
#   pwsh -NoProfile -File scripts/device-qa/regression_fixes.ps1
param(
    [string]$Serial = 'fe4ab855',
    [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/device-qa/regression-fixes')
)
. (Join-Path $PSScriptRoot 'qa_common.ps1')

# Keyboard centers measured on 1080x2400 portrait, default keyboard height.
$KEY = @{
    n         = '752 2017'
    i         = '806 1665'
    space     = '589 2196'
    edit      = '872 1494'
    selectAll = '158 1795'
}

function Tap-QaKey([string]$Name, [int]$Delay = 350) {
    $parts = $KEY[$Name].Split(' ')
    Tap-Qa ([int]$parts[0]) ([int]$parts[1]) $Delay
}

function Find-QaNodeByText([string]$Name, [string]$Text) {
    $doc = Get-QaUi $Name
    $node = $doc.SelectNodes('//node') | Where-Object { $_.text -eq $Text } | Select-Object -First 1
    if (-not $node) { throw "UI node '$Text' not found in $Name" }
    return $node
}

function Tap-QaText([string]$Name, [string]$Text, [int]$Delay = 450) {
    $node = Find-QaNodeByText $Name $Text
    $bounds = [string]$node.bounds
    $nums = [regex]::Matches($bounds, '\d+') | ForEach-Object { [int]$_.Value }
    if ($nums.Count -lt 4) { throw "Invalid bounds '$bounds' for '$Text'" }
    $x = [int](($nums[0] + $nums[2]) / 2)
    $y = [int](($nums[1] + $nums[3]) / 2)
    Write-Output "TAP '$Text' at $x,$y ($bounds)"
    Tap-Qa $x $y $Delay
}

function Save-QaScreenshot([string]$Name) {
    $remote = '/sdcard/bitap_regression_fixes.png'
    $local = Join-Path $EvidenceDir "$Name.png"
    Invoke-QaAdb @('shell', 'screencap', '-p', $remote) | Out-Null
    Invoke-QaAdb @('pull', $remote, $local) | Out-Null
}

function Assert-QaKeyboardShown([string]$Name) {
    $ime = Invoke-QaAdb @('shell', 'dumpsys', 'input_method')
    $shown = ($ime -match 'mCurId=com\.bitap\.keyboard/\.ime\.BitapImeService') -and ($ime -match 'mInputShown=true')
    Assert-Qa $Name $shown "Bitap IME shown=$shown"
}

# InputMethodManager.updateSelection only reaches the IME while Bitap is the
# active, shown keyboard; Start-QaEditor throws instead of injecting coordinates
# into an unknown layout.
Invoke-QaAdb @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP') | Out-Null
Invoke-QaAdb @('shell', 'wm', 'dismiss-keyguard') | Out-Null
Invoke-QaAdb @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0') | Out-Null
Invoke-QaAdb @('shell', 'settings', 'put', 'system', 'user_rotation', '0') | Out-Null
Invoke-QaAdb @('shell', 'ime', 'set', 'com.bitap.keyboard/.ime.BitapImeService') | Out-Null
Start-Sleep -Milliseconds 600

Write-Output '== Test 1: composing-end keeps text and does not duplicate on space =='
Start-QaEditor 'native' 'empty'
Tap-QaKey 'n'
Tap-QaKey 'i'
Start-Sleep -Milliseconds 500
Assert-QaEditor 'composing-ni' 'ni' 2
Save-QaScreenshot '01-composing-ni'

# The button is part of the activity window (the IME window is not in uiautomator
# dumps), so its real bounds come from the dump instead of a hard-coded point.
Tap-QaText 'end-composing-button' '结束组合'
Start-Sleep -Milliseconds 500
Assert-QaKeyboardShown 'end-composing-keyboard-still-shown'
Assert-QaEditor 'end-composing-keeps-text' 'ni' 2

Tap-QaKey 'space' 700
Assert-QaEditor 'space-after-end-composing' 'ni ' 3
Save-QaScreenshot '02-space-after-end-composing'

Write-Output '== Test 2: selectAll fallback refuses a partial ExtractedText window =='
# Control: on a plain EditText the same taps must really select all, proving that
# the editing page is open and the calibrated 全选 coordinate is correct. Without
# this, a missed tap on the selectall field would also look like a pass.
Start-QaEditor 'native' 'hello'
Assert-QaEditor 'selectall-control-initial' 'hello world' 11
Tap-QaKey 'edit' 700
Tap-QaKey 'selectAll' 700
Assert-QaEditor 'selectall-control-native' 'hello world' 0 11
Save-QaScreenshot '03-selectall-control'

Start-QaEditor 'selectall' 'hello'
Assert-QaEditor 'selectall-fallback-initial' 'hello world' 11
Tap-QaKey 'edit' 700
Tap-QaKey 'selectAll' 700
Assert-QaEditor 'selectall-fallback-refuses-partial-window' 'hello world' 11
Save-QaScreenshot '04-selectall-fallback'

Complete-QaReport
