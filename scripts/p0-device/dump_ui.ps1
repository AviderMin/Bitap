<#
.SYNOPSIS
  Dump current Android UI via uiautomator, save raw XML, print compact node list.
.PARAMETER Out
  Local XML output path (default .tmp/p0final/ui-last.xml).
.PARAMETER Find
  Only print nodes whose text/content-desc contains this keyword.
.PARAMETER All
  Print every node (otherwise only text/desc/EditText nodes).
#>
param(
  [string]$Out = "D:\Projects\Github\Bitap\.tmp\p0final\ui-last.xml",
  [string]$Find = "",
  [switch]$All
)
$ErrorActionPreference = "Stop"
$serial = if ($env:BITAP_DEVICE) { $env:BITAP_DEVICE } else { "fe4ab855" }
$dir = Split-Path -Parent $Out
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

adb -s $serial shell uiautomator dump /sdcard/p0_ui_dump.xml | Out-Null
adb -s $serial pull /sdcard/p0_ui_dump.xml "$Out" 2>$null | Out-Null
if (-not (Test-Path $Out)) { throw "uiautomator dump failed: $Out not created" }

[xml]$doc = Get-Content -Path $Out -Raw -Encoding UTF8
$nodes = $doc.SelectNodes("//node")
Write-Output "# rotation=$($doc.hierarchy.rotation) nodes=$($nodes.Count) xml=$Out"
foreach ($n in $nodes) {
  $cls = $n.GetAttribute("class")
  $text = $n.GetAttribute("text")
  $desc = $n.GetAttribute("content-desc")
  $bounds = $n.GetAttribute("bounds")
  $focused = $n.GetAttribute("focused")
  $selected = $n.GetAttribute("selected")
  $rid = $n.GetAttribute("resource-id")
  $interesting = (-not [string]::IsNullOrEmpty($text)) -or (-not [string]::IsNullOrEmpty($desc)) -or ($cls -match 'EditText|Button|ImageButton')
  if ($Find -ne "") {
    if (($text -like "*$Find*") -or ($desc -like "*$Find*")) {
      $tag = ($cls -split '\.')[-1]
      Write-Output ("{0,-14} text='{1}' desc='{2}' bounds={3} focused={4} selected={5} id={6}" -f $tag, $text, $desc, $bounds, $focused, $selected, $rid)
    }
  } elseif ($All -or $interesting) {
    $tag = ($cls -split '\.')[-1]
    $extra = ""
    if ($focused -eq "true") { $extra += " FOCUSED" }
    if ($selected -eq "true") { $extra += " SELECTED" }
    Write-Output ("{0,-14} text='{1}' desc='{2}' bounds={3}{4} id={5}" -f $tag, $text, $desc, $bounds, $extra, $rid)
  }
}
