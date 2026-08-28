@echo off
REM Shared by other .bat scripts. Always cd to TextSend_Desktop (parent of pack\).
REM Do not use setlocal here: cmd undoes cd on endlocal.

set "PACK_DIR=%~dp0"
if "%PACK_DIR:~-1%"=="\" set "PACK_DIR=%PACK_DIR:~0,-1%"
for %%I in ("%PACK_DIR%\..") do set "ROOT=%%~fI"
cd /d "%ROOT%" || exit /b 1

set "MAIN_JAVA=%ROOT%\src\cn.rmshadows.TextSend\application\TextSendMain.java"
if not exist "%MAIN_JAVA%" (
  echo Missing %MAIN_JAVA%
  exit /b 1
)

set "VERSION="
for /f "tokens=2 delims==" %%a in ('findstr /C:"VERSION = " "%MAIN_JAVA%"') do set "_VER=%%a"
if not defined _VER (
  echo Cannot read VERSION from TextSendMain.java
  exit /b 1
)
set "VERSION=%_VER: =%"
set "VERSION=%VERSION:"=%"
set "VERSION=%VERSION:;=%"
set "_VER="

for /f "tokens=1 delims=-" %%a in ("%VERSION%") do set "APP_VERSION=%%a"

set "DIST=%ROOT%\dist"
set "JPACKAGE_INPUT=%DIST%\jpackage-input"
set "FAT_JAR=%DIST%\Textsend_%VERSION%.jar"
set "MAIN_CLASS=application.TextSendMain"
set "ICON_PNG=%ROOT%\other\icon.png"
set "ICON_ICO=%ROOT%\other\icon.ico"
set "JP_CONSOLE_PROPS=%PACK_DIR%\win-console.properties"

echo TextSend %VERSION%  app-version %APP_VERSION%
echo Root %ROOT%
