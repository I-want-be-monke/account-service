@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0data.ps1" -Command Seed %*
exit /b %errorlevel%
