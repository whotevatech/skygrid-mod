@echo off
setlocal
cd /d "%~dp0"

echo =====================================================
echo  Sky Grid Mod - First Time Setup
echo =====================================================
echo.

set "JAR_PATH=%~dp0gradle\wrapper\gradle-wrapper.jar"
set "JAR_URL=https://github.com/gradle/gradle/raw/v8.8.0/gradle/wrapper/gradle-wrapper.jar"

if exist "%JAR_PATH%" (
    echo Gradle wrapper JAR already exists. Skipping download.
    goto done
)

echo Downloading Gradle wrapper JAR...
echo.

REM Try curl first (built into Windows 10/11)
curl -L --output "%JAR_PATH%" "%JAR_URL%"

if exist "%JAR_PATH%" goto done

REM Fallback: try PowerShell
echo Trying PowerShell download...
powershell -Command "Invoke-WebRequest -Uri '%JAR_URL%' -OutFile '%JAR_PATH%' -UseBasicParsing"

if exist "%JAR_PATH%" goto done

REM Both failed
echo.
echo -------------------------------------------------------
echo  DOWNLOAD FAILED - Try this manually:
echo  1. Open your browser and go to:
echo     %JAR_URL%
echo  2. Save the file to:
echo     %JAR_PATH%
echo -------------------------------------------------------
pause
exit /b 1

:done
echo.
echo =====================================================
echo  Setup complete!
echo.
echo  NEXT: Open IntelliJ IDEA, click File ^> Open,
echo  and select THIS folder (skygrid-mod).
echo  Wait for Gradle to sync - then run the mod via:
echo    Gradle panel ^> Tasks ^> fabric ^> runClient
echo =====================================================
echo.
pause
