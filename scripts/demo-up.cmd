@echo off
setlocal
cd /d "%~dp0.."
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0prepare-demo.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%
for %%V in (DB_NAME DB_PORT HTTP_PORT HTTPS_PORT DB_OWNER_USER DB_OWNER_PASSWORD DB_APP_USER DB_APP_PASSWORD APP_PROFILE APP_TLS_FILE TLS_PASSWORD DEMO_ADMIN_PASSWORD DEMO_OPERATOR_PASSWORD DEMO_ACCOUNTANT_PASSWORD DEMO_MANAGER_PASSWORD DEMO_CLIENT_PASSWORD BOOTSTRAP_ADMIN_PASSWORD) do set "%%V="
call "%~dp0full-up.cmd" account-service-defense .local\defense.env .local\dev.p12
exit /b %ERRORLEVEL%
