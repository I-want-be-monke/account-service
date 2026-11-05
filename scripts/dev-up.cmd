@echo off
setlocal
cd /d "%~dp0.."
where java.exe >nul 2>&1 || (echo JDK 21 is required. Set JAVA_HOME and PATH. & exit /b 1)
where docker.exe >nul 2>&1 || (echo Docker Desktop is required. & exit /b 1)
docker info >nul 2>&1 || (echo Docker Engine unavailable or access denied. Open Docker Desktop and use a normal CMD session with docker-users access. & exit /b 1)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\deployment.ps1" GuardDevUp
if errorlevel 1 exit /b 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\init-local.ps1"
if errorlevel 1 exit /b 1
docker compose -f compose.yaml -p account-service --env-file .env up -d --wait db
if errorlevel 1 exit /b 1
call mvnw.cmd -DskipTests package
if errorlevel 1 exit /b 1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\start-app.ps1"
if errorlevel 1 exit /b 1
for /L %%I in (1,1,45) do (
  curl.exe -kfsS https://localhost:8443/actuator/health/readiness >nul 2>&1 && goto READY
  ping -n 3 127.0.0.1 >nul
)
echo Application did not become ready. Check .local\logs\stderr.log and stdout.log.
exit /b 1
:READY
echo Ready: https://localhost:8443/login
echo Development credentials are in .env (DEMO_*_PASSWORD).
exit /b 0
