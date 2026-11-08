#requires -Version 5.1
# Compatibility entry point. Maintained engineering checks: docs/DEVELOPMENT.md.
[CmdletBinding()]
param([string]$BuildToolsVersion = '36.0.0', [switch]$StrictSources)
& (Join-Path $PSScriptRoot 'verify.ps1') -BuildToolsVersion $BuildToolsVersion -StrictSources:$StrictSources
exit $LASTEXITCODE
