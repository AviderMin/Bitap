#requires -Version 5.1
<#
.SYNOPSIS
    Reversibly move generated workspace files into an ignored local backup.
.DESCRIPTION
    Never deletes files or touches tracked source. Frozen corpus files, model
    weights, local.properties, .git and external source inputs are preserved.
    Stop Gradle daemons before moving caches. Use -WhatIf to preview.
#>
[CmdletBinding(SupportsShouldProcess=$true, ConfirmImpact='Low')]
param([string]$BackupName = ('cleanup-' + (Get-Date -Format 'yyyyMMdd-HHmmss')))
$ErrorActionPreference = 'Stop'
$workspaceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
if ($BackupName -notmatch '^[A-Za-z0-9_-]+$') { throw 'BackupName must be one plain directory name' }
$backupRoot = [IO.Path]::GetFullPath((Join-Path $workspaceRoot ('.local/' + $BackupName)))
if (-not $backupRoot.StartsWith($workspaceRoot + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid backup root' }
$paths = @('build','app/build','dist','.gradle','.kotlin')
$paths += @(Get-ChildItem -LiteralPath $workspaceRoot -File -Filter '*.log' | ForEach-Object { $_.Name })
foreach ($sourceDir in @('eval','scripts')) {
    $paths += @(Get-ChildItem -LiteralPath (Join-Path $workspaceRoot $sourceDir) -Directory -Filter '__pycache__' -Recurse |
        ForEach-Object { $_.FullName.Substring($workspaceRoot.Length + 1) })
}
$tmpRoot = Join-Path $workspaceRoot '.tmp'
$preserved = @('cc0','tatoeba','jieba.txt','pypinyin_pkg')
if (Test-Path -LiteralPath $tmpRoot) {
    $paths += @(Get-ChildItem -LiteralPath $tmpRoot -Force | Where-Object { $_.Name -notin $preserved } | ForEach-Object { '.tmp/' + $_.Name })
}
$tracked = @(& git -C $workspaceRoot ls-files)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect tracked files' }
$moves = @()
foreach ($relative in $paths) {
    $source = [IO.Path]::GetFullPath((Join-Path $workspaceRoot $relative))
    $destination = [IO.Path]::GetFullPath((Join-Path $backupRoot $relative))
    if (-not $source.StartsWith($workspaceRoot + '\',[StringComparison]::OrdinalIgnoreCase) -or
        -not $destination.StartsWith($backupRoot + '\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Move escapes workspace/backup' }
    if (-not (Test-Path -LiteralPath $source)) { continue }
    $gitPath = $relative.Replace('\','/')
    if ($tracked | Where-Object { $_ -eq $gitPath -or $_.StartsWith($gitPath + '/') }) { throw "Refusing tracked target: $relative" }
    if ((Get-Item -LiteralPath $source -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Refusing reparse point: $relative" }
    if (Test-Path -LiteralPath $destination) { throw "Backup destination already exists: $relative" }
    $bytes = 0L
    if (Test-Path -LiteralPath $source -PathType Container) {
        $children = @(Get-ChildItem -LiteralPath $source -Recurse -Force)
        if ($children | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw "Refusing nested reparse point: $relative" }
        foreach ($file in ($children | Where-Object { -not $_.PSIsContainer })) { $bytes += $file.Length }
    } else { $bytes = (Get-Item -LiteralPath $source).Length }
    $moves += [pscustomobject]@{Relative=$relative; Source=$source; Destination=$destination; Bytes=$bytes}
}
# All move paths and tracked-file guards are checked before the first mutation.
$completed = @()
foreach ($move in $moves) {
    if ($PSCmdlet.ShouldProcess($move.Source, ('Move into ' + $move.Destination))) {
        New-Item -ItemType Directory -Force (Split-Path -Parent $move.Destination) | Out-Null
        Move-Item -LiteralPath $move.Source -Destination $move.Destination
        $completed += $move
        # Preserve recovery information even if a later locked path fails.
        $completed | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $backupRoot 'moves.json') -Encoding UTF8
    }
}
$movedBytes = if ($completed.Count -gt 0) { ($completed | Measure-Object Bytes -Sum).Sum } else { 0 }
Write-Output ('Planned={0} Moved={1} Bytes={2} Backup={3}' -f $moves.Count,$completed.Count,$movedBytes,$backupRoot)
