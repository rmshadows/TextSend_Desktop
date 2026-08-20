# Windows .exe 安装程序（jpackage，自带 JRE）。必须在 Windows PowerShell 里跑。
# 用法：.\pack\pack-win.ps1
# 产物：dist\*.exe
$ErrorActionPreference = "Stop"
$PackDir = $PSScriptRoot
. (Join-Path $PackDir "common.ps1")

if ($env:OS -ne "Windows_NT") {
    throw ".exe 只能在 Windows 上打（jpackage 不能跨平台打包）"
}

Need "jpackage"
if ($env:SKIP_JAR -ne "1" -or -not (Test-Path $FAT_JAR)) {
    & (Join-Path $PackDir "pack-jar.ps1")
}

if (Test-Path $JPACKAGE_INPUT) { Remove-Item -Recurse -Force $JPACKAGE_INPUT }
New-Item -ItemType Directory -Force -Path $JPACKAGE_INPUT | Out-Null
Copy-Item -Force $FAT_JAR (Join-Path $JPACKAGE_INPUT "TextSend.jar")

Write-Host "==> jpackage exe"
Get-ChildItem -Path $DIST -Filter "*.exe" -ErrorAction SilentlyContinue | Remove-Item -Force

& jpackage `
    --type exe `
    --name TextSend `
    --app-version $APP_VERSION `
    --vendor "TextSend" `
    --description "局域网文字互传" `
    --dest $DIST `
    --input $JPACKAGE_INPUT `
    --main-jar TextSend.jar `
    --main-class $MAIN_CLASS `
    --win-shortcut `
    --win-menu `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options '-Dtextsend.home=$ROOTDIR'
if ($LASTEXITCODE -ne 0) { throw "jpackage exe 失败" }

Write-Host "OK  dist\ 下的 .exe"
Get-ChildItem -Path $DIST -Filter "*.exe" | ForEach-Object { Write-Host $_.FullName }
Write-Host "配置：程序目录能写就写旁边；否则用户主目录 .textsend.properties"
