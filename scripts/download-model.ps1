# Download Qwen3 0.6B LiteRT-LM (dynamic INT4) for local testing.
# The model is NOT part of the APK and is NOT committed to git.
# Usage: .\scripts\download-model.ps1
param(
    [string]$OutDir = "models",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
$url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/$fileName"
$target = Join-Path $OutDir $fileName
$expectedBytes = 343490560

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
if ((Test-Path $target) -and -not $Force) {
    $size = (Get-Item $target).Length
    if ($size -ge $expectedBytes) {
        Write-Host "Already present: $target ($size bytes)"
        exit 0
    }
}

Write-Host "Downloading $fileName (about 328 MiB) from Hugging Face..."
& curl.exe -L --fail --retry 3 --continue-at - --output $target $url
if ($LASTEXITCODE -ne 0) { throw "download failed with exit code $LASTEXITCODE" }

$size = (Get-Item $target).Length
if ($size -lt $expectedBytes) { throw "incomplete model: $size / $expectedBytes bytes" }
Write-Host "OK: $target ($size bytes)"
Write-Host "Next: adb push $target /sdcard/Download/ then import it in Bitap."
