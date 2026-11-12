@echo off
rem Windows helper: run-windows.bat [desktop|server|dist|test|jar|server-jar]
setlocal
cd /d "%~dp0"

set "MODE=%~1"
if "%MODE%"=="" set "MODE=desktop"

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)

"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Install JDK 17 from https://adoptium.net and set JAVA_HOME.
    exit /b 1
)

for /f "tokens=3" %%v in ('call "%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
for /f "delims=." %%m in ("%JAVA_VER%") do set "JAVA_MAJOR=%%m"
if not defined JAVA_MAJOR (
    echo [ERROR] Could not determine the Java version of "%JAVA_EXE%".
    exit /b 1
)
if "%JAVA_MAJOR%"=="1" (
    echo [ERROR] Java %JAVA_VER% is too old. Mindustry needs JDK 17 or newer. Set JAVA_HOME to a JDK 17 install.
    exit /b 1
)
if %JAVA_MAJOR% LSS 17 (
    echo [ERROR] Java %JAVA_VER% is too old. Mindustry needs JDK 17 or newer. Set JAVA_HOME to a JDK 17 install.
    exit /b 1
)

if /i "%MODE%"=="desktop"    call gradlew.bat desktop:run & goto :end
if /i "%MODE%"=="server"     call gradlew.bat server:run & goto :end
if /i "%MODE%"=="dist"       call gradlew.bat desktop:dist server:dist & goto :end
if /i "%MODE%"=="test"       call gradlew.bat tests:test & goto :end
if /i "%MODE%"=="jar"        "%JAVA_EXE%" -jar desktop\build\libs\ExploreCraft.jar & goto :end
if /i "%MODE%"=="server-jar" "%JAVA_EXE%" -jar server\build\libs\server-release.jar & goto :end

echo Usage: run-windows.bat [desktop^|server^|dist^|test^|jar^|server-jar]
exit /b 2

:end
exit /b %errorlevel%
