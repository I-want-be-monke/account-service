param([ValidateSet('start','second','stop')][string]$Action = 'start')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $root
$project = 'account-service-e11'
$isolated = Join-Path $root '.local\e11'
$envFile = Join-Path $isolated 'stand.env'
$tls = Join-Path $isolated 'tls.p12'
$pem = Join-Path $isolated 'tls.pem'
$second = "$project-app2"
$required = [ordered]@{ DB_NAME='account_service_e11'; DB_PORT='55438'; HTTP_PORT='18048'; HTTPS_PORT='18448';
    DB_OWNER_USER='account_owner'; DB_APP_USER='account_app'; APP_PROFILE='dev' }
$secrets = @('DB_OWNER_PASSWORD','DB_APP_PASSWORD','TLS_PASSWORD','DEMO_ADMIN_PASSWORD','DEMO_OPERATOR_PASSWORD',
    'DEMO_ACCOUNTANT_PASSWORD','DEMO_MANAGER_PASSWORD','DEMO_CLIENT_PASSWORD')

function New-Secret {
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 24
    try { $rng.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $rng.Dispose() }
}

function Assert-StandDatabase {
    Assert-ContainerLabel "$project-db-1" 'db'
    $port = & docker port "$project-db-1" 5432/tcp
    if ($LASTEXITCODE -ne 0 -or $port -cne '127.0.0.1:55438') { throw 'E11 PostgreSQL port mismatch.' }
    $mounts = & docker inspect --format '{{json .Mounts}}' "$project-db-1"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect E11 PostgreSQL mounts.' }
    $mountList = ConvertFrom-Json $mounts
    $volume = ($mountList | Where-Object Destination -ceq '/var/lib/postgresql/data').Name
    if ($volume -cne "${project}_account-data") { throw 'E11 PostgreSQL volume mismatch.' }
    $database = & docker exec "$project-db-1" psql -XAt --no-password -U account_owner -d account_service_e11 -c 'select current_database()'
    if ($LASTEXITCODE -ne 0 -or $database -cne 'account_service_e11') { throw 'E11 PostgreSQL database mismatch.' }
}

function Assert-ContainerLabel([string]$Container, [string]$Service) {
    $metadata = & docker inspect --format '{{json .Config.Labels}}' $Container
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $Container." }
    $identity = $metadata | ConvertFrom-Json
    if ($identity.'com.docker.compose.project' -cne $project -or $identity.'com.docker.compose.service' -cne $Service) {
        throw 'E11 container identity mismatch.'
    }
}

try {
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop engine is unavailable.' }
    if ($Action -eq 'start' -and -not (Test-Path -LiteralPath $envFile)) {
        [IO.Directory]::CreateDirectory($isolated) | Out-Null
        $lines = @($required.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" })
        $lines += @($secrets | ForEach-Object { "$_=$(New-Secret)" })
        [IO.File]::WriteAllLines($envFile, $lines, [Text.Encoding]::ASCII)
    }
    $settings = @{}
    foreach ($line in (Get-Content -LiteralPath $envFile)) {
        if ($line -cnotmatch '^([A-Z_]+)=(.+)$' -or $settings.ContainsKey($Matches[1])) { throw 'Invalid E11 settings.' }
        $settings[$Matches[1]] = $Matches[2]
    }
    foreach ($key in $required.Keys) {
        if ($settings[$key] -cne $required[$key]) { throw "E11 setting $key must match the dedicated stand." }
    }
    foreach ($key in $secrets) {
        if ($settings[$key] -cnotmatch '^[0-9a-f]{48}$') { throw "Invalid E11 secret $key." }
    }
    foreach ($key in @($required.Keys) + $secrets) { Set-Item "Env:$key" $settings[$key] }
    $env:APP_TLS_FILE = $tls
    $compose = @('compose','-p',$project,'--env-file',$envFile,'-f','compose.yaml','-f','tests/performance/compose.e11.yaml','--profile','full')

    if ($Action -eq 'start') {
        $keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { 'keytool.exe' }
        if (-not (Test-Path -LiteralPath $tls)) {
            & $keytool -genkeypair -alias accounts-local -keyalg RSA -keysize 3072 -validity 365 `
                -dname 'CN=localhost, OU=E11 Load Stand' -ext 'SAN=dns:localhost,ip:127.0.0.1' -storetype PKCS12 `
                -keystore $tls -storepass:env TLS_PASSWORD -noprompt
            if ($LASTEXITCODE -ne 0) { throw 'Cannot create E11 TLS certificate.' }
        }
        & $keytool -exportcert -rfc -alias accounts-local -keystore $tls -storepass:env TLS_PASSWORD -file $pem
        if ($LASTEXITCODE -ne 0) { throw 'Cannot export E11 TLS public certificate.' }
        & docker @compose up -d --build --wait --wait-timeout 160 app
        if ($LASTEXITCODE -ne 0) { throw 'E11 stand startup failed.' }
        Assert-StandDatabase
        & docker exec "$project-db-1" psql -X --no-password -v ON_ERROR_STOP=1 -U account_owner -d account_service_e11 `
            -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'
        if ($LASTEXITCODE -ne 0) { throw 'Cannot initialize E11 SQL telemetry.' }
        & curl.exe --cacert $pem -fsS 'https://localhost:18448/actuator/health/readiness'
        if ($LASTEXITCODE -ne 0) { throw 'E11 HTTPS readiness failed.' }
    } elseif ($Action -eq 'second') {
        Assert-StandDatabase
        $existing = & docker ps -aq --filter "name=^/$second$"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect E11 app2.' }
        if ($existing) {
            Assert-ContainerLabel $existing 'app'
            & docker start $existing
        } else {
            & docker @compose run -d --no-deps --name $second -p '127.0.0.1:18449:18448' app
        }
        if ($LASTEXITCODE -ne 0) { throw 'Cannot start E11 app2.' }
        for ($i=0; $i -lt 60; $i++) {
            & curl.exe --cacert $pem --fail --silent --max-time 3 'https://localhost:18449/actuator/health/readiness'
            if ($LASTEXITCODE -eq 0) { exit 0 }
            Start-Sleep -Seconds 1
        }
        throw 'E11 app2 readiness failed.'
    } else {
        $runningSecond = & docker ps -q --filter "name=^/$second$" --filter "label=com.docker.compose.project=$project"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect E11 app2.' }
        if ($runningSecond) {
            & docker stop $runningSecond | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Cannot stop E11 app2.' }
        }
        & docker @compose stop app db
        if ($LASTEXITCODE -ne 0) { throw 'Cannot stop E11 Compose services.' }
        Write-Host 'E11 stand stopped. Its volume and local evidence remain.'
    }
    exit 0
} catch {
    Write-Host "E11 STAND FAILED: $($_.Exception.Message)"
    exit 1
}
