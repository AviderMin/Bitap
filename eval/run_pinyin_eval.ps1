param(
    [ValidateSet('baseline', 'after')]
    [string]$Stage = 'baseline',
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$env:PINYIN_EVAL_STAGE = $Stage
Push-Location $RepoRoot
try {
    & .\gradlew.bat :app:testDebugUnitTest --tests "com.bitap.keyboard.core.PinyinQualityEvalTest" --rerun-tasks --console=plain
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
Write-Host "Report: $RepoRoot\build\pinyin-quality-eval-$Stage.txt"
