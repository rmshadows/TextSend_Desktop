# 绿色目录：自带精简 JRE（jpackage app-image，不是 Linux .AppImage）
# 用法：.\pack\pack-appimage.ps1
# 产物：dist\TextSend\  以及 dist\TextSend-<version>-win-x64.zip
$ErrorActionPreference = "Stop"
$PackDir = $PSScriptRoot
. (Join-Path $PackDir "common.ps1")

Need "jpackage"
if ($env:SKIP_JAR -ne "1" -or -not (Test-Path $FAT_JAR)) {
    & (Join-Path $PackDir "pack-jar.ps1")
}

foreach ($p in @($JPACKAGE_INPUT, (Join-Path $DIST "TextSend"))) {
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
}
New-Item -ItemType Directory -Force -Path $JPACKAGE_INPUT | Out-Null
Copy-Item -Force $FAT_JAR (Join-Path $JPACKAGE_INPUT "TextSend.jar")

Write-Host "==> jpackage app-image"
# $ROOTDIR 必须单引号，交给 jpackage 运行时展开
$iconArgs = Get-WinIconArgs
& jpackage `
    --type app-image `
    --name TextSend `
    --app-version $APP_VERSION `
    --vendor "TextSend" `
    --description "局域网文字互传" `
    --dest $DIST `
    --input $JPACKAGE_INPUT `
    --main-jar TextSend.jar `
    --main-class $MAIN_CLASS `
    @iconArgs `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options '-Dtextsend.home=$ROOTDIR'
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image 失败" }

$Archive = Join-Path $DIST "TextSend-$VERSION-win-x64.zip"
if (Test-Path $Archive) { Remove-Item -Force $Archive }
Compress-Archive -Path (Join-Path $DIST "TextSend") -DestinationPath $Archive
Write-Host "OK  $(Join-Path $DIST 'TextSend\')"
Write-Host "启动：$(Join-Path $DIST 'TextSend\TextSend.exe')"
Write-Host "OK  $Archive"
Write-Host "配置：安装/解压目录下的 textsend.properties"
