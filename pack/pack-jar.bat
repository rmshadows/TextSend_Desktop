@echo off
REM fat JAR - target machine needs Java 17+
REM Usage: pack\pack-jar.bat
REM Output: dist\Textsend_<version>.jar
setlocal EnableExtensions

call "%~dp0common.bat" || exit /b 1

where mvn >nul 2>&1
if errorlevel 1 (
  echo Missing mvn. Add Maven to PATH.
  exit /b 1
)

if not exist "%DIST%" mkdir "%DIST%"

if "%SKIP_JAR%"=="1" if exist "%FAT_JAR%" (
  echo Skip Maven, jar exists: %FAT_JAR%
  exit /b 0
)

echo ==^> Maven shade
call mvn -q -DskipTests -f "%ROOT%\pom.xml" package
if errorlevel 1 (
  echo mvn package failed
  exit /b 1
)

if not exist "%ROOT%\target\TextSend.jar" (
  echo Missing target\TextSend.jar after Maven build
  exit /b 1
)

copy /Y "%ROOT%\target\TextSend.jar" "%FAT_JAR%" >nul
if exist "%DIST%\TextSend.jar" del /f /q "%DIST%\TextSend.jar"

echo OK %FAT_JAR%
echo Run: java -jar "%FAT_JAR%"

endlocal
