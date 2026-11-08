# Sample Bitap PSS via dumpsys meminfo and append a one-line CSV record.
# Usage: .\pss.ps1 -OutFile .tmp/device-qa/sample.txt [-Tag scenario] [-Serial fe4ab855]
param(
  [Parameter(Mandatory=$true)][string]$OutFile,
  [string]$Tag = "sample",
  [string]$Serial = "fe4ab855"
)
$ErrorActionPreference = "Stop"
$dir = Split-Path -Parent $OutFile
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$raw = adb -s $Serial shell dumpsys meminfo com.bitap.keyboard
$raw | Set-Content -Path $OutFile -Encoding UTF8
$total = ($raw | Select-String -Pattern 'TOTAL PSS:\s*(\d+)').Matches[0].Groups[1].Value
$rss = ($raw | Select-String -Pattern 'TOTAL RSS:\s*(\d+)').Matches[0].Groups[1].Value
$java = ($raw | Select-String -Pattern 'Java Heap:\s*(\d+)').Matches[0].Groups[1].Value
$native = ($raw | Select-String -Pattern 'Native Heap:\s*(\d+)').Matches[0].Groups[1].Value
$ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
Write-Output ("{0},{1},TOTAL_PSS_KB={2},TOTAL_RSS_KB={3},Java_KB={4},Native_KB={5}" -f $ts, $Tag, $total, $rss, $java, $native)
