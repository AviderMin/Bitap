<#
.SYNOPSIS
  Tap an element found by content-desc / text in the current UI dump.
  Usage: .\tap.ps1 -Match "字母 n"        (content-desc contains)
         .\tap.ps1 -Match "空格" -Text    (match text attr instead of content-desc)
  Prints matched element and taps its center.
#>
param(
  [Parameter(Mandatory=$true)][string]$Match,
  [switch]$Text,
  [switch]$Exact,
  [string]$Out = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) '.tmp/p0final/ui-tap.xml')
)
$ErrorActionPreference = "Stop"
$serial = if ($env:BITAP_DEVICE) { $env:BITAP_DEVICE } else { "fe4ab855" }
& "$PSScriptRoot\dump_ui.ps1" -Out $Out | Out-Null
[xml]$doc = Get-Content -Path $Out -Raw -Encoding UTF8
$hits = @()
foreach ($n in $doc.SelectNodes("//node")) {
  $val = if ($Text) { $n.GetAttribute("text") } else { $n.GetAttribute("content-desc") }
  if ([string]::IsNullOrEmpty($val)) { continue }
  if ($Exact) { $ok = ($val -eq $Match) } else { $ok = ($val -like "*$Match*") }
  if ($ok) {
    $b = $n.GetAttribute("bounds")
    if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
      $cx = [int](([int]$matches[1] + [int]$matches[3]) / 2)
      $cy = [int](([int]$matches[2] + [int]$matches[4]) / 2)
      $hits += [pscustomobject]@{ TText=$n.GetAttribute("text"); Desc=$n.GetAttribute("content-desc"); Bounds=$b; X=$cx; Y=$cy }
    }
  }
}
if ($hits.Count -eq 0) { Write-Error "no element matched '$Match' (Text=$Text)"; exit 2 }
if ($hits.Count -gt 1) {
  Write-Output "WARN multiple matches ($($hits.Count)), using first:"
  $hits | Format-Table TText,Desc,Bounds,X,Y -AutoSize | Out-String | Write-Output
}
$h = $hits[0]
Write-Output ("TAP match='{0}' text='{1}' desc='{2}' bounds={3} -> ({4},{5})" -f $Match, $h.TText, $h.Desc, $h.Bounds, $h.X, $h.Y)
adb -s $serial shell input tap $h.X $h.Y
