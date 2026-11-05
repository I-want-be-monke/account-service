@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0backup-task.ps1" %*
exit /b %ERRORLEVEL%
