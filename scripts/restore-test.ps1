param(
    [string]$Backup,
    [string]$Target = 'account_service_restore_test',
    [ValidateRange(1024, 65535)][int]$HttpsPort = 18443,
    [switch]$Stop
)
. "$PSScriptRoot\ops-common.ps1"
$isolatedDb = 'account_service_restore_test'
$containerName = 'account-service-restore-test'
$label = 'ru.tsu.accounts.restore-test'
$local = Join-Path $RepoRoot '.local\restore-test'
$statePath = Join-Path $local 'state.json'
$timer = [Diagnostics.Stopwatch]::StartNew()
$log = $null
$lock = $null
$startedContainer = $null
$app = $null
$success = $false

function New-RestoreSecret {
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32
    try { $random.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant() }
    finally { $random.Dispose() }
}

function Find-RestoreContainer([switch]$OwnedOnly) {
    $arguments = @('ps', '-aq', '--filter', "name=^/$containerName$")
    if ($OwnedOnly) { $arguments += @('--filter', "label=$label=true") }
    return Invoke-Docker $arguments -TimeoutSeconds 15
}

function Stop-RestoreApp([int]$ProcessId, [string]$Token) {
    if ($ProcessId -le 0 -or $Token -cnotmatch '^[0-9a-f]{32}$') { throw 'Invalid isolated app state.' }
    if (-not (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue)) { return }
    $instance = Get-WmiObject -Class Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction Stop
    if ($instance.Name -eq 'java.exe' -and $instance.CommandLine -match ('(?:^|\s)-Daccount\.restore\.token=' + $Token + '(?:\s|$)')) {
        Stop-Process -Id $ProcessId
    } else { Write-OpsLog $log 'Saved PID belongs to another process. It was left alone.' }
}

try {
    if ($Target -cne $isolatedDb) { throw "Restore is restricted to $isolatedDb in a new isolated container." }
    [IO.Directory]::CreateDirectory($local) | Out-Null
    $lock = [IO.File]::Open((Join-Path $local '.restore.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    if ($Stop) {
        if (-not (Test-Path -LiteralPath $statePath -PathType Leaf)) { throw 'No isolated restore run is registered.' }
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        $found = Find-RestoreContainer -OwnedOnly
        if ($state.container -cnotmatch '^[0-9a-f]{64}$' -or ($found -and -not $state.container.StartsWith($found))) {
            throw 'The isolated restore container identity could not be confirmed.'
        }
        Stop-RestoreApp $state.pid $state.token
        if ($found) { Invoke-Docker @('stop', $state.container) | Out-Null }
        Remove-Item -LiteralPath $statePath
        Write-OpsLog $null 'Isolated restore stopped. Verification logs remain.'
        exit 0
    }

    $token = [Guid]::NewGuid().ToString('N')
    $run = Join-Path $local ('run-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + $token.Substring(0, 8))
    [IO.Directory]::CreateDirectory($run) | Out-Null
    $log = Join-Path $run 'restore.log'
    Write-OpsLog $log 'Restore drill started. Selecting backup and checking SHA-256.'
    $selection = $null
    if ($Backup) {
        $selection = Read-VerifiedBackup $Backup
    } else {
        foreach ($directory in (Get-BackupDirectories (Get-BackupRoot))) {
            try { $selection = Read-VerifiedBackup $directory.FullName; break }
            catch { Write-OpsLog $log "Skipped unverified backup $($directory.Name)." }
        }
        if (-not $selection) { throw 'No backup with matching SHA-256 was found.' }
    }
    Write-OpsLog $log "SHA256 OK $($selection.Manifest.sha256). Snapshot start UTC $($selection.Manifest.snapshotStartedUtc)."
    $existing = Find-RestoreContainer
    if ($existing -or (Test-Path -LiteralPath $statePath)) { throw 'The isolated restore target already exists. Use restore-test-down.cmd.' }

    $jar = (Resolve-Path -LiteralPath (Join-Path $RepoRoot 'target\account-service.jar')).ProviderPath
    $tls = if ($env:ACCOUNT_RESTORE_TLS_FILE) { $env:ACCOUNT_RESTORE_TLS_FILE } else { Join-Path $RepoRoot '.local\dev.p12' }
    $tls = (Resolve-Path -LiteralPath $tls).ProviderPath
    $tlsSecret = $env:TLS_PASSWORD
    if (-not $tlsSecret) {
        foreach ($line in (Get-Content -LiteralPath (Join-Path $RepoRoot '.env'))) {
            if ($line -match '^TLS_PASSWORD=(.+)$') { $tlsSecret = $Matches[1]; break }
        }
    }
    if (-not $tlsSecret) { throw 'TLS_PASSWORD and the local PKCS12 store are required.' }
    if ($HttpsPort -in @(8080, 8443)) { throw 'Choose an HTTPS port distinct from the working instance.' }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $HttpsPort)
    try { $listener.Start() } finally { $listener.Stop() }

    $owner = $selection.Manifest.sourceOwner
    $ownerPassword = New-RestoreSecret
    $appPassword = New-RestoreSecret
    $env:POSTGRES_PASSWORD = $ownerPassword
    $env:ACCOUNT_DB_PASSWORD = $appPassword
    $init = Join-Path $RepoRoot 'docker\init-users.sql'
    try {
        $startedContainer = Invoke-Docker @('run', '--detach', '--rm', '--name', $containerName,
            '--label', "$label=true", '--tmpfs', '/var/lib/postgresql/data', '--publish', '127.0.0.1::5432',
            '--mount', "type=bind,source=$init,target=/docker-entrypoint-initdb.d/01-users.sql,readonly",
            '--env', "POSTGRES_USER=$owner", '--env', 'POSTGRES_DB=postgres', '--env', 'POSTGRES_PASSWORD',
            '--env', 'ACCOUNT_DB_PASSWORD', 'postgres:17.11-bookworm') -TimeoutSeconds 180
    } finally {
        Remove-Item Env:POSTGRES_PASSWORD, Env:ACCOUNT_DB_PASSWORD -ErrorAction SilentlyContinue
    }
    if ($startedContainer -cnotmatch '^[0-9a-f]{64}$') { throw 'Unexpected isolated container ID.' }
    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            Invoke-Docker @('exec', $startedContainer, 'pg_isready', '-h', '127.0.0.1', '-d', 'postgres', '-U', $owner) `
                -TimeoutSeconds 5 | Out-Null
            $ready = $true
            break
        } catch { Start-Sleep -Milliseconds 500 }
    }
    if (-not $ready) { throw 'The isolated PostgreSQL did not become ready.' }
    Write-OpsLog $log 'Isolated PostgreSQL is ready. Checking archive structure.'
    $toc = Invoke-Docker @('exec', '-i', $startedContainer, 'pg_restore', '--list') -InputFile $selection.Archive
    if ($toc -notmatch 'TABLE DATA public financial_operation' -or $toc -notmatch 'TABLE DATA public audit_event') {
        throw 'Archive lacks required entries.'
    }
    Invoke-Docker @('exec', $startedContainer, 'createdb', '--no-password', '-U', $owner, $isolatedDb) | Out-Null
    Invoke-Docker @('exec', '-i', $startedContainer, 'pg_restore', '--no-password', '--exit-on-error', '--single-transaction',
        '-U', $owner, '-d', $isolatedDb) -InputFile $selection.Archive -TimeoutSeconds 1800 | Out-Null
    Write-OpsLog $log 'Schema and data restored in one transaction. Reconciling PostgreSQL.'
    $sql = Invoke-Docker @('exec', '-i', $startedContainer, 'psql', '-XqAt', '--no-password', '-v', 'ON_ERROR_STOP=1',
        '-U', $owner, '-d', $isolatedDb, '-f', '-') -InputFile (Join-Path $PSScriptRoot 'restore-check.sql')
    $sql | ConvertFrom-Json | Out-Null
    [IO.File]::WriteAllText((Join-Path $run 'reconciliation.json'), $sql)
    Write-OpsLog $log "SQL OK $sql"

    $published = Invoke-Docker @('port', $startedContainer, '5432/tcp')
    if ($published -notmatch '^127\.0\.0\.1:([0-9]+)$') { throw 'Unexpected isolated PostgreSQL publication.' }
    $jdbc = "jdbc:postgresql://127.0.0.1:$($Matches[1])/$isolatedDb"
    Remove-Item Env:SPRING_APPLICATION_JSON, Env:BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    $env:SPRING_DATASOURCE_PASSWORD = $appPassword
    $env:SPRING_FLYWAY_PASSWORD = $ownerPassword
    $env:DB_OWNER_PASSWORD = $ownerPassword
    $env:DB_APP_PASSWORD = $appPassword
    $env:TLS_PASSWORD = $tlsSecret
    $env:TLS_KEY_STORE = ([Uri]::new($tls)).AbsoluteUri
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
    $app = Start-Process -FilePath $java -WindowStyle Hidden -PassThru -WorkingDirectory $run `
        -ArgumentList @("-Daccount.restore.token=$token", '-jar', ('"' + $jar + '"'),
            '--spring.config.location=classpath:/', '--spring.profiles.active=restore-test',
            "--spring.datasource.url=$jdbc", '--spring.datasource.username=account_app',
            "--spring.flyway.url=$jdbc", "--spring.flyway.user=$owner", '--spring.flyway.enabled=true',
            '--server.address=127.0.0.1', "--server.port=$HttpsPort",
            '--server.ssl.enabled=true', '--account.http.enabled=false', '--logging.file.name=account-service.log') `
        -RedirectStandardOutput (Join-Path $run 'stdout.log') -RedirectStandardError (Join-Path $run 'stderr.log')
    Write-OpsLog $log 'Isolated app started. Waiting for Flyway validation and HTTPS readiness.'
    $url = "https://localhost:$HttpsPort"
    $readiness = $null
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($app.HasExited) { break }
        $readiness = & curl.exe --insecure --fail --silent --max-time 2 "$url/actuator/health/readiness"
        if ($LASTEXITCODE -eq 0 -and $readiness -match '"status"\s*:\s*"UP"') { break }
        Start-Sleep -Seconds 1
    }
    if ($readiness -notmatch '"status"\s*:\s*"UP"') { throw "Isolated application did not become ready. See $run." }
    $state = @{ container = $startedContainer; pid = $app.Id; token = $token; httpsPort = $HttpsPort; log = $log }
    [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json))
    Write-OpsLog $log ('RESTORE READY in {0:N2}s {1}/login. Check sign-in, statement, report and audit.' -f $timer.Elapsed.TotalSeconds, $url)
    $success = $true
    exit 0
} catch {
    Write-OpsLog $log "RESTORE FAILED: $($_.Exception.Message)"
    exit 1
} finally {
    if (-not $success -and -not $Stop) {
        if ($app -and -not $app.HasExited) { $app.Kill(); $app.WaitForExit() }
        if ($startedContainer -cmatch '^[0-9a-f]{64}$') {
            try { Invoke-Docker @('stop', $startedContainer) | Out-Null }
            catch { Write-Warning "Could not stop isolated container $startedContainer." }
        }
    }
    if ($lock) { $lock.Dispose() }
}
