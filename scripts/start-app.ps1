$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$local = Join-Path $root '.local'
$logs = Join-Path $local 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$pidFile = Join-Path $local 'app.pid'
if (Test-Path $pidFile) {
    $appPid = [int](Get-Content $pidFile -Raw)
    if (Get-Process -Id $appPid -ErrorAction SilentlyContinue) {
        throw 'Application is already running; use scripts\dev-down.cmd first'
    }
    Remove-Item $pidFile
}
$java = 'java.exe'
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
$process = Start-Process -FilePath $java -WorkingDirectory $root -WindowStyle Hidden `
    -ArgumentList @('-jar', 'target\account-service.jar', '--spring.profiles.active=dev') `
    -RedirectStandardOutput (Join-Path $logs 'stdout.log') `
    -RedirectStandardError (Join-Path $logs 'stderr.log') -PassThru
[IO.File]::WriteAllText($pidFile, [string]$process.Id)
