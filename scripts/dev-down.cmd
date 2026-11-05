@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\stop-app.ps1"
if errorlevel 1 exit /b 1
if exist .env docker compose --env-file .env stop db
echo Data remains in the named Docker volume.
