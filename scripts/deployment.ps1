param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('FullUp', 'FullDown', 'GuardDevUp', 'GuardDevDown')]
    [string]$Action,
    [string]$Project = 'account-service',
    [string]$EnvFile,
    [string]$TlsFile
)

$ErrorActionPreference = 'Stop'
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$defaultProject = 'account-service'
$defaultEnv = Join-Path $root '.env'
$defaultTls = Join-Path $root '.local\dev.p12'

function Test-HostApplication {
    $pidFile = Join-Path $root '.local\app.pid'
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) { return $false }
    $appPid = 0
    if (-not [int]::TryParse((Get-Content -LiteralPath $pidFile -Raw).Trim(), [ref]$appPid)) {
        throw 'Invalid .local\app.pid. Verify the local Java process before switching launch modes.'
    }
    return $null -ne (Get-Process -Id $appPid -ErrorAction SilentlyContinue)
}

if ($Action -in 'GuardDevUp', 'GuardDevDown') {
    $container = & docker ps --filter "label=com.docker.compose.project=$defaultProject" `
        --filter 'label=com.docker.compose.service=app' --format '{{.ID}}'
    if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable.' }
    if ($container) { throw 'The full Compose app is running. Use scripts\full-down.cmd to switch modes.' }
    if ($Action -eq 'GuardDevUp' -and (Test-HostApplication)) {
        throw 'The host app is already running. Use scripts\dev-down.cmd before rebuilding.'
    }
    exit 0
}

if ($Project -cnotmatch '^[a-z0-9][a-z0-9_-]*$') {
    throw 'Compose project name must use lower-case letters, digits, underscores or hyphens.'
}
if ($Project -eq $defaultProject) {
    if ($EnvFile -or $TlsFile) {
        throw 'The default project uses .env and .local\dev.p12. Use a separate project for other settings.'
    }
    if (Test-HostApplication) {
        throw 'The host app is running. Use scripts\dev-down.cmd before switching the default project.'
    }
    $EnvFile = $defaultEnv
    $TlsFile = $defaultTls
} elseif (-not $EnvFile) {
    throw 'An isolated Compose project requires an explicit environment file.'
}

$ErrorActionPreference = 'Continue'
& docker info 2>$null | Out-Null
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable. Start Docker Desktop.' }
if ($Action -eq 'FullUp' -and $Project -eq $defaultProject) {
    & (Join-Path $PSScriptRoot 'init-local.ps1')
}
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw 'Compose environment file was not found.' }
$resolvedEnv = (Resolve-Path -LiteralPath $EnvFile).Path
if ($Project -ne $defaultProject -and $resolvedEnv -ieq $defaultEnv) {
    throw 'Use a separate environment file for the isolated Compose project.'
}
$composeArgs = @('compose', '-f', (Join-Path $root 'compose.yaml'), '-p', $Project,
    '--env-file', $resolvedEnv, '--profile', 'full')

if ($Action -eq 'FullDown') {
    & docker @composeArgs stop app db
    if ($LASTEXITCODE -ne 0) { throw 'Compose could not stop the selected project.' }
    Write-Host "Stopped Compose project $Project. PostgreSQL volume is preserved."
    exit 0
}

if (-not $TlsFile) { $TlsFile = $defaultTls }
if (-not (Test-Path -LiteralPath $TlsFile -PathType Leaf)) { throw 'The local PKCS12 certificate was not found.' }
$env:APP_TLS_FILE = (Resolve-Path -LiteralPath $TlsFile).Path
# Inspect the effective configuration without sending interpolated secrets to the terminal.
$ErrorActionPreference = 'Continue'
$resolved = & docker @composeArgs config --format json 2>$null
$ErrorActionPreference = 'Stop'
if ($LASTEXITCODE -ne 0) { throw 'Compose settings are invalid. Check required variables and port values.' }
try { $model = ($resolved -join "`n") | ConvertFrom-Json }
catch { throw 'Compose returned an invalid configuration. Check the environment file and Docker Compose version.' }
$application = $model.services.app.environment
if ([string]::IsNullOrWhiteSpace($application.TLS_PASSWORD)) { throw 'TLS_PASSWORD is required.' }
if ($application.SPRING_PROFILES_ACTIVE -notin 'dev', 'pilot') { throw 'APP_PROFILE must be dev or pilot.' }
if ($application.SPRING_PROFILES_ACTIVE -eq 'dev') {
    foreach ($key in 'DEMO_ADMIN_PASSWORD', 'DEMO_OPERATOR_PASSWORD', 'DEMO_ACCOUNTANT_PASSWORD',
        'DEMO_MANAGER_PASSWORD', 'DEMO_CLIENT_PASSWORD') {
        if ($application.$key.Length -lt 12 -or $application.$key.Length -gt 128) {
            throw "$key must contain 12-128 characters for the dev profile."
        }
    }
}
$published = @($model.services.db.ports + $model.services.app.ports)
$ports = @($published | ForEach-Object { [int]$_.published })
if ($published.Count -ne 3 -or ($published | Where-Object host_ip -ne '127.0.0.1') -or
    ($ports | Where-Object { $_ -lt 1 -or $_ -gt 65535 }) -or
    (@($ports | Select-Object -Unique)).Count -ne 3) {
    throw 'Configure three distinct localhost ports for PostgreSQL, HTTP and HTTPS.'
}
if ($Project -ne $defaultProject -and ($ports | Where-Object { $_ -in 55432, 8080, 8443 })) {
    throw 'An isolated Compose project must use alternate DB_PORT, HTTP_PORT and HTTPS_PORT values.'
}

& docker @composeArgs up -d --build --wait --wait-timeout 120 app
if ($LASTEXITCODE -ne 0) { throw 'Compose startup failed. Check the app and db container logs.' }
Write-Host "Ready: https://localhost:$($application.SERVER_PORT)/login (Compose project $Project)"
