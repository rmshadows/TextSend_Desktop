@echo off
REM One-click Windows pack: fat JAR + portable dir + .exe installer (WiX 3)
REM For WiX 4/5 (JDK 24+): pack\pack-win-wix5.bat (do not rely on choose.bat 5 in PowerShell)
REM Usage from TextSend_Desktop: pack\pack.bat
setlocal EnableExtensions

call "%~dp0common.bat" || exit /b 1

if /i not "%OS%"=="Windows_NT" (
  echo pack.bat is for Windows. On Linux or macOS use ./pack/pack.sh
  exit /b 1
)

call "%~dp0pack-jar.bat" || exit /b 1
set SKIP_JAR=1
call "%~dp0pack-appimage.bat" || exit /b 1
call "%~dp0pack-win.bat"
if errorlevel 1 (
  echo.
  echo Warning: .exe installer skipped. JAR and portable zip are in %DIST%
)

echo.
echo ==^> Done. Output in %DIST%:
dir /b "%DIST%"
if exist "%DIST%\TextSend\TextSend-console.exe" (
  echo.
  echo Daily:   "%DIST%\TextSend\TextSend.exe"
  echo Debug:   "%DIST%\TextSend\TextSend-console.exe"
)

endlocal
