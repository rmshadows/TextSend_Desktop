# 被其它 .ps1 dot-source。目录：TextSend_Desktop\
$ErrorActionPreference = "Stop"

if (-not $PackDir) {
    $PackDir = $PSScriptRoot
}
$Root = (Resolve-Path (Join-Path $PackDir "..")).Path
Set-Location $Root

$MainJava = Join-Path $Root "src\cn.rmshadows.TextSend\application\TextSendMain.java"
if (-not (Test-Path $MainJava)) {
    throw "找不到 $MainJava"
}

$m = [regex]::Match((Get-Content -Raw $MainJava), 'VERSION = "([^"]+)"')
if (-not $m.Success) {
    throw "读不到 VERSION"
}
$VERSION = $m.Groups[1].Value
$APP_VERSION = ($VERSION -split "-", 2)[0]

$DIST = Join-Path $Root "dist"
$JPACKAGE_INPUT = Join-Path $DIST "jpackage-input"
$FAT_JAR = Join-Path $DIST "Textsend_$VERSION.jar"
$MAIN_CLASS = "application.TextSendMain"
$ICON_PNG = Join-Path $Root "other\icon.png"
$ICON_ICO = Join-Path $Root "other\icon.ico"

function Need([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "缺少命令：$Name（请把 JDK 17+ 和 Maven 加到 PATH）"
    }
}

Write-Host "TextSend $VERSION  (app-version $APP_VERSION)"
Write-Host "项目目录 $Root"

function Get-WinIconArgs {
    if (Test-Path $ICON_ICO) {
        return @("--icon", $ICON_ICO)
    }
    Write-Warning "找不到 $ICON_ICO，安装包将用默认 Java 图标"
    return @()
}
