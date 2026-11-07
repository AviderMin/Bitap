# Item 1: consecutive 2x/3x pending confirmations including repeated pinyin,
# punctuation and backspace. Portrait 1080x2400.
# Key coordinates (portrait, Bitap keyboard):
#   row1 y=1673: q61 w168 e275 r382 t488 y594 u700 i806 o912 p1018
#   row2 y=1849: a106 s215 d324 f433 g541 h650 j757 k866 l974
#   row3 y=2025: z222 x328 c434 v540 b646 n752 m858 shift88 backspace991
#   bottom y=2204: 123=69, 中/英=186, comma=293, space=590, period=887, enter=1004
#   candidate row y=1516; AI=871, gear=1004
# Taps are chained inside one adb shell with 50 ms gaps so key events stay
# discrete while remaining fast enough to exercise the 30 ms query debounce.
param(
  [string]$EvidenceDir = "D:\Projects\Github\Bitap\.tmp\p0final\precheck-item1",
  [string]$Tag = "precheck-item1",
  [string]$BaseUrl = "http://127.0.0.1:8769"
)
$ErrorActionPreference = "Stop"
$serial = if ($env:BITAP_DEVICE) { $env:BITAP_DEVICE } else { "fe4ab855" }
$dump = Join-Path $PSScriptRoot "dump_ui.ps1"
$shot = Join-Path $PSScriptRoot "screenshot.ps1"
$ev = $EvidenceDir
if (-not (Test-Path $ev)) { New-Item -ItemType Directory -Force -Path $ev | Out-Null }

# key coordinate table: 0.05 s between taps
$KEY = @{
  q="61 1673"; w="168 1673"; e="275 1673"; r="382 1673"; t="488 1673"; y="594 1673"; u="700 1673"; i="806 1673"; o="912 1673"; p="1018 1673";
  a="106 1849"; s="215 1849"; d="324 1849"; f="433 1849"; g="541 1849"; h="650 1849"; j="757 1849"; k="866 1849"; l="974 1849";
  z="222 2025"; x="328 2025"; c="434 2025"; v="540 2025"; b="646 2025"; n="752 2025"; m="858 2025";
  shift="88 2025"; backspace="991 2025";
  clear="121 1009"; selall="306 1009";
  comma="293 2204"; space="590 2204"; period="887 2204"; enter="1004 2204";
  textarea="540 600"; ai="871 1516"; gear="1004 1516"
}
function Invoke-Taps([string[]]$keys) {
  $parts = @()
  foreach ($k in $keys) {
    if (-not $KEY.ContainsKey($k)) { throw "no key $k" }
    $parts += "input tap $($KEY[$k])"
  }
  $cmd = ($parts -join "; sleep 0.05; ")
  adb -s $serial shell $cmd | Out-Null
}
function Get-EditText($xmlPath) {
  [xml]$doc = Get-Content -Path $xmlPath -Raw -Encoding UTF8
  $edit = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" } | Select-Object -First 1
  $status = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "len=*" } | Select-Object -First 1
  return [pscustomobject]@{ Text=if($edit){$edit.GetAttribute("text")}else{$null}; Status=if($status){$status.GetAttribute("text")}else{$null} }
}
function Save-Step([string]$name) {
  $xml = Join-Path $ev "$Tag-$name.xml"
  $png = Join-Path $ev "$Tag-$name.png"
  & $dump -Out $xml | Out-Null
  & $shot -Out $png | Out-Null
  $f = Get-EditText $xml
  Write-Host ("STEP {0,-8} text='{1}' status='{2}'" -f $name, $f.Text, $f.Status)
  return [string]$f.Text
}
function Reset-Text {
  Invoke-Taps @("clear")
  Start-Sleep -Milliseconds 400
  Invoke-Taps @("textarea")
  Start-Sleep -Milliseconds 700
}

# open page fresh
adb -s $serial shell am start -a android.intent.action.VIEW -d "$BaseUrl/p0.html?item=1" -n mark.via/.Shell | Out-Null
Start-Sleep -Seconds 2
Invoke-Taps @("clear")
Start-Sleep -Milliseconds 400
Invoke-Taps @("textarea")
Start-Sleep -Milliseconds 900

Write-Output "== Item1 pending confirmation sequences: evidence=$ev =="
$results = @()

# Scenario A: three consecutive pending confirmations ni/ni/nihao
Invoke-Taps @("n","i","space")
Start-Sleep -Milliseconds 1000
$t1 = Save-Step "a1-ni-space"
Invoke-Taps @("n","i","space")
Start-Sleep -Milliseconds 1000
$t2 = Save-Step "a2-ni-space"
Invoke-Taps @("n","i","h","a","o","space")
Start-Sleep -Milliseconds 1200
$t3 = Save-Step "a3-nihao-space"
$results += [pscustomobject]@{step="A-final"; expected="你好你好你好"; actual=$t3; pass=($t3 -eq "你好你好你好")}

# Scenario B: two pending + punctuation + backspace + third pending
Reset-Text
Invoke-Taps @("n","i","space")
Start-Sleep -Milliseconds 900
$b1 = Save-Step "b1-ni-space"
Invoke-Taps @("n","i","space")
Start-Sleep -Milliseconds 900
$b2 = Save-Step "b2-ni-space"
Invoke-Taps @("comma")
Start-Sleep -Milliseconds 400
$b3 = Save-Step "b3-comma"
Invoke-Taps @("period")
Start-Sleep -Milliseconds 400
$b4 = Save-Step "b4-period"
Invoke-Taps @("backspace")
Start-Sleep -Milliseconds 400
$b5 = Save-Step "b5-backspace"
Invoke-Taps @("n","i","h","a","o","space")
Start-Sleep -Milliseconds 1200
$b6 = Save-Step "b6-nihao-space"
$results += [pscustomobject]@{step="B-final"; expected="你好你好，你好"; actual=$b6; pass=($b6 -eq "你好你好，你好")}

Write-Output ""
Write-Output "== Item1 summary =="
foreach ($r in $results) {
  $verdict = if ($r.pass -eq $true) { "PASS" } else { "FAIL" }
  Write-Output ("[{0}] {1}: expected='{2}' actual='{3}'" -f $verdict, $r.step, $r.expected, $r.actual)
}
