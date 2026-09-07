@echo off
REM Build (Maven shade) then run TextSend. Double-click or: run.bat
REM Skip rebuild: run.bat skip
REM Needs: JDK 17+ and Maven on PATH
setlocal EnableExtensions
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
  echo Missing java. Add JDK 17+ to PATH.
  exit /b 1
)

set "JAR=%cd%\target\TextSend.jar"
if /i "%~1"=="skip" goto run
if /i "%~1"=="fast" goto run

where mvn >nul 2>&1
if errorlevel 1 (
  echo Missing mvn. Add Maven to PATH.
  exit /b 1
)

echo ==^> mvn package
call mvn -q -DskipTests -f pom.xml package
if errorlevel 1 (
  echo mvn package failed
  exit /b 1
)

:run
if not exist "%JAR%" (
  echo Missing %JAR%
  echo Run without "skip" so Maven can build it.
  exit /b 1
)

echo ==^> java -jar "%JAR%"
java -jar "%JAR%"
set "EC=%ERRORLEVEL%"
if not "%EC%"=="0" (
  echo Exit code %EC%
  pause
)
exit /b %EC%
