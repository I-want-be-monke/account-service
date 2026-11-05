$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$pidFile = Join-Path $root '.local\app.pid'
if (-not (Test-Path $pidFile)) { return }
$appPid = [int](Get-Content $pidFile -Raw)
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $appPid"
if ($process) {
    if ($process.CommandLine -notmatch 'account-service\.jar') { throw 'PID is not the account-service process' }
    Stop-Process -Id $appPid
}
Remove-Item $pidFile
