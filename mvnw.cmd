@echo off
setlocal
set "PROJECT_ROOT=%~dp0"
set "MAVEN_CMD=%PROJECT_ROOT%.mvn\dists\apache-maven-3.9.11\bin\mvn.cmd"
if not exist "%MAVEN_CMD%" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_ROOT%scripts\bootstrap-maven.ps1"
  if errorlevel 1 exit /b 1
)
if not exist "%MAVEN_CMD%" exit /b 1
pushd "%PROJECT_ROOT%"
call "%MAVEN_CMD%" %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
