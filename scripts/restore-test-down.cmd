@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0restore-test.ps1" -Stop
exit /b %ERRORLEVEL%
