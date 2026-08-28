@echo off
REM Portable app-image with bundled JRE
REM Usage: pack\pack-appimage.bat
REM Output: dist\TextSend\ and dist\TextSend-<version>-win-x64.zip
setlocal EnableExtensions

call "%~dp0common.bat" || exit /b 1

where jpackage >nul 2>&1
if errorlevel 1 (
  echo Missing jpackage. Add JDK 17+ to PATH.
  exit /b 1
)

if "%SKIP_JAR%"=="1" (
  if not exist "%FAT_JAR%" call "%~dp0pack-jar.bat" || exit /b 1
) else (
  call "%~dp0pack-jar.bat" || exit /b 1
)

if exist "%JPACKAGE_INPUT%" rmdir /s /q "%JPACKAGE_INPUT%"
if exist "%DIST%\TextSend" rmdir /s /q "%DIST%\TextSend"
mkdir "%JPACKAGE_INPUT%"
copy /Y "%FAT_JAR%" "%JPACKAGE_INPUT%\TextSend.jar" >nul

echo ==^> jpackage app-image
REM Explicit Swing/AWT modules; ZXing stays in the fat JAR classpath
set "JP_MODULES=java.base,java.desktop,java.datatransfer,java.sql,java.xml,jdk.charsets"
if not exist "%PACK_DIR%\win-console.properties" (
  echo Missing %PACK_DIR%\win-console.properties
  exit /b 1
)
if exist "%ICON_ICO%" (
  jpackage --type app-image --name TextSend --app-version %APP_VERSION% --vendor TextSend --description TextSend --dest "%DIST%" --input "%JPACKAGE_INPUT%" --main-jar TextSend.jar --main-class %MAIN_CLASS% --add-modules "%JP_MODULES%" --add-launcher "TextSend-console=%JP_CONSOLE_PROPS%" --icon "%ICON_ICO%" --java-options "-Dfile.encoding=UTF-8" --java-options "-Dtextsend.home=$ROOTDIR"
) else (
  echo Warning: missing %ICON_ICO%, using default Java icon
  jpackage --type app-image --name TextSend --app-version %APP_VERSION% --vendor TextSend --description TextSend --dest "%DIST%" --input "%JPACKAGE_INPUT%" --main-jar TextSend.jar --main-class %MAIN_CLASS% --add-modules "%JP_MODULES%" --add-launcher "TextSend-console=%JP_CONSOLE_PROPS%" --java-options "-Dfile.encoding=UTF-8" --java-options "-Dtextsend.home=$ROOTDIR"
)
if errorlevel 1 (
  echo jpackage app-image failed
  exit /b 1
)

set "ARCHIVE=%DIST%\TextSend-%VERSION%-win-x64.zip"
if exist "%ARCHIVE%" del /f /q "%ARCHIVE%"
tar -a -c -f "%ARCHIVE%" -C "%DIST%" TextSend
if errorlevel 1 (
  echo zip failed, need tar from Windows 10 or later
  exit /b 1
)

echo OK %DIST%\TextSend\
echo OK %ARCHIVE%
if exist "%DIST%\TextSend\TextSend-console.exe" (
  echo Debug: %DIST%\TextSend\TextSend-console.exe
)

endlocal
