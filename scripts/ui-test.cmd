@echo off
setlocal
cd /d "%~dp0.."
node.exe "tests\ui\check.cjs" %*
exit /b %ERRORLEVEL%
