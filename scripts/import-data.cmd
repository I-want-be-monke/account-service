@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0import-data.ps1" %*
exit /b %ERRORLEVEL%
