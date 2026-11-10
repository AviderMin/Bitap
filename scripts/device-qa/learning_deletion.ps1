# Uses actual candidate taps and the settings Delete button. The private store
# is read only for evidence; no seeding, clearing or direct writes bypass UI.
param(
    [string]$Serial = 'fe4ab855',
    [string]$EvidenceDir = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/device-qa/learning-deletion')
)
. (Join-Path $PSScriptRoot 'qa_common.ps1')
function Get-QaLearning([string]$Name) {
    $raw = Invoke-QaAdb @('shell', 'run-as', 'com.bitap.keyboard', 'cat', 'shared_prefs/bitap_user_lexicon.xml')
    $raw | Set-Content (Join-Path $EvidenceDir "$Name-store.xml") -Encoding UTF8
    [xml]$doc = $raw
    $entryNode = $doc.SelectSingleNode('//string[@name="entries"]')
    if ($entryNode) {
        foreach ($line in ($entryNode.InnerText -split "`n")) {
            $parts = $line -split "`t"
            if ($parts.Count -ge 3) { [pscustomobject]@{ Pinyin = $parts[0]; Word = $parts[1]; Count = [int]$parts[2] } }
        }
    }
}
function Enter-QaPinyin([string]$Pinyin) {
    $keys = @{
        c = @(437,2017); e = @(275,1665); s = @(215,1841); h = @(650,1841); i = @(807,1665)
        n = @(752,2017); a = @(106,1841); o = @(915,1665)
    }
    foreach ($letter in $Pinyin.ToCharArray()) {
        $point = $keys[[string]$letter]
        if (-not $point) { throw "Uncalibrated key: $letter" }
        Tap-Qa $point[0] $point[1] 150
    }
    Start-Sleep -Milliseconds 900
    Tap-Qa 75 1494 850
}
function Find-QaLearningRow([string]$Name, [string]$Word) {
    for ($index = 0; $index -lt 18; $index++) {
        $doc = Get-QaUi "$Name-scroll-$index"
        $node = $doc.SelectNodes('//node') | Where-Object { $_.text -ceq $Word } | Select-Object -First 1
        if ($node) { return $node }
        Invoke-QaAdb @('shell', 'input', 'swipe', '540', '2020', '540', '800', '350') | Out-Null
        Start-Sleep -Milliseconds 350
    }
    throw "Learning row not found: $Word"
}
function Tap-QaNode($Node) {
    $numbers = [regex]::Matches($Node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    Tap-Qa ([int](($numbers[0] + $numbers[2]) / 2)) ([int](($numbers[1] + $numbers[3]) / 2)) 650
}

Invoke-QaAdb @('shell', 'ime', 'set', 'com.bitap.keyboard/.ime.BitapImeService') | Out-Null
$before = @(Get-QaLearning '00-before')
if ($before | Where-Object { $_.Pinyin -eq 'ceshi' -and $_.Word -eq '测试' }) {
    throw 'The QA target 测试 already exists; refusing to delete a pre-existing learned entry'
}
Start-QaEditor 'native' 'empty'
Enter-QaPinyin 'ceshi'
Assert-QaEditor '01-learned-text' '测试' 2
$learned = @(Get-QaLearning '01-learned')
Assert-Qa 'learned-store' ([bool]($learned | Where-Object { $_.Pinyin -eq 'ceshi' -and $_.Word -eq '测试' })) 'ceshi -> 测试 persisted through actual candidate selection'
$pidBefore = (Invoke-QaAdb @('shell', 'pidof', 'com.bitap.keyboard')).Trim()

# Open settings while the same IME/engine is alive, then locate its real row.
Tap-Qa 1005 1494 800
$target = Find-QaLearningRow '02-settings' '测试'
# Compose accessibility flattens these rows into siblings. Match the Delete
# button overlapping the target title vertically, not a shared card ancestor.
$titleBounds = [regex]::Matches($target.bounds, '\d+') | ForEach-Object { [int]$_.Value }
$buttons = @($target.OwnerDocument.SelectNodes('//node[@text="删除"]') | Where-Object {
    $buttonBounds = [regex]::Matches($_.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    $buttonBounds[0] -gt $titleBounds[2] -and $buttonBounds[1] -lt $titleBounds[3] -and $buttonBounds[3] -gt $titleBounds[1]
})
if ($buttons.Count -ne 1) { throw 'Could not identify the single Delete button belonging to 测试' }
Tap-QaNode $buttons[0]
$deleted = @(Get-QaLearning '03-deleted')
Assert-Qa 'settings-delete' (-not ($deleted | Where-Object Word -eq '测试')) 'Actual settings Delete removed the learned entry'

Invoke-QaAdb @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
Start-Sleep -Milliseconds 700
# Reopen only the debug editor task; the main IME process must stay alive.
Start-QaEditor 'native' 'empty'
Enter-QaPinyin 'nihao'
Assert-QaEditor '04-continued-input' '你好' 2
$continued = @(Get-QaLearning '04-continued')
$pidAfter = (Invoke-QaAdb @('shell', 'pidof', 'com.bitap.keyboard')).Trim()
Assert-Qa 'same-ime-process' ($pidBefore -eq $pidAfter) "IME pid before=$pidBefore after=$pidAfter"
Assert-Qa 'continued-input-no-revival' (-not ($continued | Where-Object Word -eq '测试')) 'A later real candidate commit did not write back the deleted entry'
Assert-Qa 'other-learning-preserved' (@($continued | Where-Object Word -eq '你好').Count -gt 0) 'Other learned entries still exist'
$oldNihao = ($learned | Where-Object { $_.Pinyin -eq 'nihao' -and $_.Word -eq '你好' } | Measure-Object Count -Sum).Sum
$newNihao = ($continued | Where-Object { $_.Pinyin -eq 'nihao' -and $_.Word -eq '你好' } | Measure-Object Count -Sum).Sum
Assert-Qa 'continued-input-persisted' ($newNihao -gt $oldNihao) "nihao count before=$oldNihao after=$newNihao"
$missing = @($before | Where-Object {
    $entry = $_
    -not ($continued | Where-Object { $_.Pinyin -eq $entry.Pinyin -and $_.Word -eq $entry.Word -and $_.Count -ge $entry.Count })
})
Assert-Qa 'preexisting-learning-preserved' ($missing.Count -eq 0) "Missing or reduced pre-existing entries: $($missing.Count)"

Tap-Qa 1005 1494 800
Find-QaLearningRow '05-reopened-settings' '你好' | Out-Null
$reopened = @(Get-QaLearning '05-reopened-settings')
Assert-Qa 'settings-reopen-no-revival' (-not ($reopened | Where-Object Word -eq '测试')) 'Reopened settings and persisted store agree'

Invoke-QaAdb @('shell', 'am', 'force-stop', 'com.bitap.keyboard') | Out-Null
Invoke-QaAdb @('shell', 'ime', 'set', 'com.bitap.keyboard/.ime.BitapImeService') | Out-Null
Start-QaEditor 'native' 'empty'
Enter-QaPinyin 'nihao'
$restarted = @(Get-QaLearning '06-restarted')
Assert-Qa 'restart-no-revival' (-not ($restarted | Where-Object Word -eq '测试')) 'Full process restart and new input did not resurrect the deleted entry'
Complete-QaReport
