# fat JAR：对方机器需要 Java 17+
# 用法：.\pack\pack-jar.ps1
# 产物：dist\TextSend.jar
$ErrorActionPreference = "Stop"
$PackDir = $PSScriptRoot
. (Join-Path $PackDir "common.ps1")

Need "mvn"
New-Item -ItemType Directory -Force -Path $DIST | Out-Null

if ($env:SKIP_JAR -eq "1" -and (Test-Path $FAT_JAR)) {
    Write-Host "跳过 Maven（已有 $FAT_JAR）"
    exit 0
}

Write-Host "==> Maven shade"
& mvn -q -DskipTests package
if ($LASTEXITCODE -ne 0) { throw "mvn package 失败" }

$Built = Join-Path $Root "target\TextSend.jar"
if (-not (Test-Path $Built)) {
    throw "Maven 没有打出 target\TextSend.jar"
}
Copy-Item -Force $Built $FAT_JAR
Write-Host "OK  $FAT_JAR"
Write-Host "运行：java -jar `"$FAT_JAR`""
Write-Host "配置文件会写在 jar 同一目录的 textsend.properties"
