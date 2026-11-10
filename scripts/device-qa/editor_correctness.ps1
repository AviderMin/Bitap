# Actual IME gestures and editing-page actions, with no direct controller calls.
param(
    [string]$Serial = 'fe4ab855',
    [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/device-qa/editor-correctness')
)
. (Join-Path $PSScriptRoot 'qa_common.ps1')
Invoke-QaAdb @('shell', 'ime', 'set', 'com.bitap.keyboard/.ime.BitapImeService') | Out-Null
$emojiText = 'A' + [char]::ConvertFromUtf32(0x1F600) + 'B'
foreach ($field in @('web', 'compose', 'native')) {
    Start-QaEditor $field
    Assert-QaEditor "$field-initial" $emojiText 4
    Tap-Qa 995 2017
    Assert-QaEditor "$field-delete-B" ($emojiText.Substring(0, 3)) 3
    Tap-Qa 995 2017
    Assert-QaEditor "$field-delete-emoji" 'A' 1

    Start-QaEditor $field
    Move-QaCursor -1
    Assert-QaEditor "$field-cursor-4-to-3" $emojiText 3
    Move-QaCursor -1
    Assert-QaEditor "$field-cursor-3-to-1" $emojiText 1
    Move-QaCursor 1
    Assert-QaEditor "$field-cursor-1-to-3" $emojiText 3

    if ($field -ne 'web') {
        Start-QaEditor $field 'emoji' 3
        Assert-QaEditor "$field-preset-3" $emojiText 3
        Move-QaCursor -1
        Assert-QaEditor "$field-preset-3-to-1" $emojiText 1
        Start-QaEditor $field 'emoji' 1
        Move-QaCursor 1
        Assert-QaEditor "$field-preset-1-to-3" $emojiText 3
    }

    Start-QaEditor $field 'empty'
    # EditorInputInfo resets to Chinese for these plain text hosts.
    Tap-Qa 191 2210
    Tap-Qa 650 1841 150
    Tap-Qa 275 1665 150
    Tap-Qa 974 1841 500
    Assert-QaEditor "$field-typed-hel" 'hel' 3
    Tap-Qa 170 1494
    Assert-QaEditor "$field-complete" 'hello ' 6
    Tap-Qa 872 1494
    Tap-Qa 285 2150
    Assert-QaEditor "$field-undo" 'hel' 3
    Tap-Qa 790 2150
    Assert-QaEditor "$field-redo" 'hello ' 6
}
Complete-QaReport
