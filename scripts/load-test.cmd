@echo off
setlocal
cd /d "%~dp0.."
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 "tests\performance\LoadHarness.java" %*
) else (
  java.exe -Dfile.encoding=UTF-8 "tests\performance\LoadHarness.java" %*
)
exit /b %ERRORLEVEL%
