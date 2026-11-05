@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deployment.ps1" FullDown %*
exit /b %ERRORLEVEL%
