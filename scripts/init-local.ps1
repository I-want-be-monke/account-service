$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$envPath = Join-Path $root '.env'
function New-Secret {
    $bytes = New-Object byte[] 24
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}
if (-not (Test-Path $envPath)) {
    $lines = @('DB_NAME=accounts', 'DB_PORT=55432', 'DB_OWNER_USER=account_owner',
        "DB_OWNER_PASSWORD=$(New-Secret)", 'DB_APP_USER=account_app',
        "DB_APP_PASSWORD=$(New-Secret)", "TLS_PASSWORD=$(New-Secret)")
    foreach ($role in @('ADMIN','OPERATOR','ACCOUNTANT','MANAGER','CLIENT')) {
        $lines += "DEMO_${role}_PASSWORD=$(New-Secret)"
    }
    [IO.File]::WriteAllLines($envPath, $lines, (New-Object Text.UTF8Encoding($false)))
    Write-Host 'Created .env with independent random passwords. Keep this file private.'
}
$values = @{}
Get-Content $envPath | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $values[$matches[1]] = $matches[2] }
}
$cert = Join-Path $root '.local\dev.p12'
if (-not (Test-Path $cert)) {
    if (-not $values.TLS_PASSWORD) { throw 'TLS_PASSWORD is missing in .env' }
    New-Item -ItemType Directory -Force -Path (Split-Path $cert) | Out-Null
    $keytool = 'keytool.exe'
    if ($env:JAVA_HOME) { $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe' }
    $env:ACCOUNT_SERVICE_KEYTOOL_PASSWORD = $values.TLS_PASSWORD
    try {
        & $keytool -genkeypair -alias accounts-local -keyalg RSA -keysize 3072 -validity 365 `
            -dname 'CN=localhost, OU=Dev, O=Student, C=RU' `
            -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 `
            -keystore $cert -storepass:env ACCOUNT_SERVICE_KEYTOOL_PASSWORD -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'keytool failed to create the development certificate' }
    } finally {
        Remove-Item Env:ACCOUNT_SERVICE_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    }
}
