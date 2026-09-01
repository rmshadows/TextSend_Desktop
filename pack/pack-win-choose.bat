@echo off
REM Dispatcher: WiX 3 -> pack-win.bat, WiX 4/5 -> pack-win-wix5.bat
REM Usage:
REM   pack\pack-win-choose.bat 5
REM   pack\pack-win-wix5.bat
REM From PowerShell prefer:
REM   cmd /c pack-win-choose.bat 5
REM   .\pack-win-wix5.bat
setlocal EnableExtensions

echo arg1=[%~1] all=[%*]

set "ARG=%~1"
if not defined ARG set "ARG=%WIX_VER%"

if /i "%ARG%"=="5" goto do5
if /i "%ARG%"=="4" goto do5
if /i "%ARG%"=="2" goto do5
if /i "%ARG%"=="wix5" goto do5
if /i "%ARG%"=="wix4" goto do5
if /i "%ARG%"=="new" goto do5
if /i "%ARG%"=="3" goto do3
if /i "%ARG%"=="1" goto do3
if /i "%ARG%"=="wix3" goto do3
if /i "%ARG%"=="old" goto do3

if defined ARG (
  echo Unknown: %ARG%
  echo Use 3 ^(WiX 3^) or 5 ^(WiX 4/5^).
  echo From PowerShell:  cmd /c pack-win-choose.bat 5
  echo Or run:           pack\pack-win-wix5.bat
  exit /b 1
)

echo.
echo jpackage Windows .exe  -  pick WiX
echo   1^) WiX 3     candle.exe / light.exe     JDK 17+
echo   2^) WiX 4/5   wix.exe                    JDK 24+ ^(JDK 25 included^)
echo.
set /p ARG=Choose 1 or 2: 
if /i "%ARG%"=="2" goto do5
if /i "%ARG%"=="5" goto do5
if /i "%ARG%"=="wix5" goto do5
if /i "%ARG%"=="1" goto do3
if /i "%ARG%"=="3" goto do3
if /i "%ARG%"=="wix3" goto do3
if not defined ARG goto do3
echo Unknown: %ARG%
exit /b 1

:do5
echo ==^> WiX 4/5  (pack-win-wix5.bat)
call "%~dp0pack-win-wix5.bat"
exit /b %ERRORLEVEL%

:do3
echo ==^> WiX 3  (pack-win.bat)
call "%~dp0pack-win.bat"
exit /b %ERRORLEVEL%
