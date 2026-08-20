# 一键（Windows PowerShell）：fat JAR + 绿色目录 + .exe 安装程序
# 用法（在 TextSend_Desktop 目录）：
#   powershell -ExecutionPolicy Bypass -File .\pack\pack.ps1
$ErrorActionPreference = "Stop"
$PackDir = $PSScriptRoot
. (Join-Path $PackDir "common.ps1")

if ($env:OS -ne "Windows_NT") {
    throw "pack.ps1 是给 Windows PowerShell 用的。Linux / macOS 请跑 ./pack/pack.sh"
}

& (Join-Path $PackDir "pack-jar.ps1")
$env:SKIP_JAR = "1"
& (Join-Path $PackDir "pack-appimage.ps1")
& (Join-Path $PackDir "pack-win.ps1")

Write-Host ""
Write-Host "==> 完成，产物在 $DIST"
Get-ChildItem $DIST | ForEach-Object { Write-Host ("  " + $_.Name) }
