@echo off
echo =====================================================
echo  Sky Grid Mod - Launch Minecraft with mod
echo =====================================================
echo.
echo Starting Gradle runClient task...
echo (This will download Minecraft + Fabric on first run - may take a few minutes)
echo.
call gradlew.bat runClient
pause
