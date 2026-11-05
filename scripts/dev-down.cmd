@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\deployment.ps1" GuardDevDown
if errorlevel 1 exit /b 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\stop-app.ps1"
if errorlevel 1 exit /b 1
if exist .env docker compose -f compose.yaml -p account-service --env-file .env stop db
if errorlevel 1 exit /b 1
echo Data remains in the named Docker volume.
