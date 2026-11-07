# Item 3: real InputConnection backspace deletion on Unicode clusters.
# Opens local HTML page via Via + adb reverse; for each case: focus textarea,
# press backspace once, dump EditText text + selection before/after, screenshot.
# Key coordinates (portrait 1080x2400, density 440, Bitap keyboard):
#   textarea center (540,600); backspace (991,2025) [row3 right]
#   space (590,2204); comma (293,2204); period (887,2204); enter (1004,2204)
#   page buttons: 清空 (121,1009), 全选 (306,1009)
param(
  [string]$EvidenceDir = "D:\Projects\Github\Bitap\.tmp\p0final\precheck",
  [string]$Tag = "precheck-item3",
  [string]$BaseUrl = "http://127.0.0.1:8769"
)
$ErrorActionPreference = "Stop"
$serial = if ($env:BITAP_DEVICE) { $env:BITAP_DEVICE } else { "fe4ab855" }
$dump = Join-Path $PSScriptRoot "dump_ui.ps1"
$shot = Join-Path $PSScriptRoot "screenshot.ps1"
$tap  = Join-Path $PSScriptRoot "tap.ps1"
$ev = $EvidenceDir
if (-not (Test-Path $ev)) { New-Item -ItemType Directory -Force -Path $ev | Out-Null }

function Get-UiField($xmlPath) {
  [xml]$doc = Get-Content -Path $xmlPath -Raw -Encoding UTF8
  $edit = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("class") -eq "android.widget.EditText" } | Select-Object -First 1
  $status = $doc.SelectNodes("//node") | Where-Object { $_.GetAttribute("text") -like "len=*" } | Select-Object -First 1
  return [pscustomobject]@{
    Text = if ($edit) { $edit.GetAttribute("text") } else { $null }
    Status = if ($status) { $status.GetAttribute("text") } else { $null }
  }
}

$cases = @(
  @{Key="family"; Name="family emoji U+1F468 ZWJ..."; Pre="👨‍👩‍👧‍👦"; ExpectAfter=""; Note="整簇删除，空"},
  @{Key="skin"; Name="skin tone U+1F44D U+1F3FD"; Pre="👍🏽"; ExpectAfter=""; Note="整簇删除，空"},
  @{Key="flag"; Name="flag CN U+1F1E8 U+1F1F3"; Pre="🇨🇳"; ExpectAfter=""; Note="整簇删除，空"},
  @{Key="accent"; Name="e + U+0301"; Pre="é"; ExpectAfter=""; Note="组合序列整簇删除，空"},
  @{Key="emoji"; Name="plain emoji U+1F600"; Pre="😀"; ExpectAfter=""; Note="单个 emoji 删除，空"},
  @{Key="hanzi"; Name="hanzi 汉字"; Pre="汉字"; ExpectAfter="汉"; Note="删最后一个汉字，剩「汉」"},
  @{Key="selection"; Name="selection 选中文本"; Pre="选中文本"; ExpectAfter=""; Note="已有选区一次删除，空"}
)

Write-Output "== Item3 Unicode deletion: evidence=$ev tag=$Tag =="
$results = @()
foreach ($c in $cases) {
  $key = $c.Key
  adb -s $serial shell am start -a android.intent.action.VIEW -d "$BaseUrl/unicode.html?case=$key" -n mark.via/.Shell | Out-Null
  Start-Sleep -Seconds 2
  adb -s $serial shell input tap 540 600
  Start-Sleep -Milliseconds 900
  if ($key -eq "selection") {
    & $tap -Match "全选" -Text | Out-Null
    Start-Sleep -Milliseconds 600
  }
  # poll up to 5 s for the prefilled value
  $before = $null; $beforeXml = $null
  for ($i = 0; $i -lt 5; $i++) {
    $beforeXml = Join-Path $ev "$Tag-$key-before.xml"
    & $dump -Out $beforeXml | Out-Null
    $before = Get-UiField $beforeXml
    if ($before.Text -eq $c.Pre) { break }
    Start-Sleep -Milliseconds 700
  }
  $beforePng = Join-Path $ev "$Tag-$key-before.png"
  & $shot -Out $beforePng | Out-Null

  adb -s $serial shell input tap 991 2025
  Start-Sleep -Milliseconds 800
  $afterXml = Join-Path $ev "$Tag-$key-after.xml"
  $afterPng = Join-Path $ev "$Tag-$key-after.png"
  & $dump -Out $afterXml | Out-Null
  & $shot -Out $afterPng | Out-Null
  $after = Get-UiField $afterXml

  $preOk = ($before.Text -eq $c.Pre) -or ($key -eq "accent" -and $before.Status -like "*65,301*")
  $afterOk = ($after.Text -eq $c.ExpectAfter) -or ($c.ExpectAfter -eq "" -and $after.Status -like "len=0 sel=0-0*")
  $ok = $preOk -and $afterOk
  $verdict = if ($ok) { "PASS" } else { "FAIL" }
  Write-Output ("[{0}] {1,-10} before={2} | after={3} | status={4} | expect {5}->'{6}' ({7})" -f $verdict, $key, $before.Text, $after.Text, $after.Status, $c.Pre, $c.ExpectAfter, $c.Note)
  $results += [pscustomobject]@{ case=$key; verdict=$verdict; before=$before.Text; after=$after.Text; status=$after.Status; expected=$c.ExpectAfter; beforeXml=$beforeXml; afterXml=$afterXml; beforePng=$beforePng; afterPng=$afterPng }
}
Write-Output ("ITEM3 SUMMARY: {0}/{1} PASS" -f ($results | Where-Object { $_.verdict -eq 'PASS' }).Count, $results.Count)
