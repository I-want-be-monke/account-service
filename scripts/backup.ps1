. "$PSScriptRoot\ops-common.ps1"
$log = $null
$stage = $null
$lock = $null
try {
    $backupRoot = Get-BackupRoot
    [IO.Directory]::CreateDirectory($backupRoot) | Out-Null
    $log = Join-Path $backupRoot 'backup.log'
    $lockPath = Join-Path $backupRoot '.backup.lock'
    $lock = [IO.File]::Open($lockPath, 'OpenOrCreate', 'ReadWrite', 'None')
    Write-OpsLog $log 'Backup started.'

    $keep = 14
    if ($env:ACCOUNT_BACKUP_KEEP -and (-not [int]::TryParse($env:ACCOUNT_BACKUP_KEEP, [ref]$keep) -or $keep -lt 1)) {
        throw 'ACCOUNT_BACKUP_KEEP must be a positive integer.'
    }
    $minimumFree = 128
    if ($env:ACCOUNT_BACKUP_MIN_FREE_MB -and (-not [int]::TryParse($env:ACCOUNT_BACKUP_MIN_FREE_MB, [ref]$minimumFree) -or $minimumFree -lt 1)) {
        throw 'ACCOUNT_BACKUP_MIN_FREE_MB must be a positive integer.'
    }
    $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($backupRoot))
    if ($drive.AvailableFreeSpace -lt ([long]$minimumFree * 1MB)) {
        throw "Insufficient space in backup destination. Require at least $minimumFree MiB free."
    }

    $envFile = if ($env:ACCOUNT_OPS_ENV_FILE) { $env:ACCOUNT_OPS_ENV_FILE } else { '.env' }
    $project = if ($env:ACCOUNT_OPS_COMPOSE_PROJECT) { $env:ACCOUNT_OPS_COMPOSE_PROJECT } else { 'account-service' }
    $compose = @('compose', '--env-file', (Resolve-Path -LiteralPath $envFile).ProviderPath, '--project-name', $project)
    $container = Invoke-Docker ($compose + @('ps', '-q', 'db')) -TimeoutSeconds 15
    if ($container -cnotmatch '^[0-9a-f]{12,64}$') { throw 'The selected Compose PostgreSQL service is not running.' }
    $owner = Invoke-Docker @('exec', $container, 'printenv', 'POSTGRES_USER')
    $database = Invoke-Docker @('exec', $container, 'printenv', 'POSTGRES_DB')
    if ($owner -cnotmatch '^[a-z_][a-z0-9_]{0,62}$' -or $database -cnotmatch '^[a-z_][a-z0-9_]{0,62}$') {
        throw 'Unsupported PostgreSQL role or database name.'
    }
    $tool = Invoke-Docker @('exec', $container, 'pg_dump', '--version')
    if ($tool -notmatch '^pg_dump \(PostgreSQL\) 17\.') { throw 'PostgreSQL 17 dump tools are required.' }

    $started = [DateTime]::UtcNow
    $stamp = $started.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $stage = Join-Path $backupRoot "stage-$stamp"
    $destination = Join-Path $backupRoot "backup-$stamp"
    [IO.Directory]::CreateDirectory($stage) | Out-Null
    $archive = Join-Path $stage 'database.dump'
    Invoke-Docker @('exec', $container, 'pg_dump', '--no-password', '-Fc', '-U', $owner, '-d', $database,
        '--exclude-table-data=public.spring_session', '--exclude-table-data=public.spring_session_attributes') `
        -OutputFile $archive -TimeoutSeconds 1800
    $toc = Invoke-Docker @('exec', '-i', $container, 'pg_restore', '--list') -InputFile $archive
    if ($toc -notmatch 'TABLE DATA public financial_operation' -or $toc -notmatch 'TABLE DATA public audit_event' -or
        $toc -notmatch 'TABLE DATA public flyway_schema_history') { throw 'Dump lacks required PostgreSQL archive entries.' }
    Invoke-Docker @('exec', '-i', $container, 'pg_restore', '--exit-on-error', '--file=/dev/null') `
        -InputFile $archive -TimeoutSeconds 1800 | Out-Null
    $hash = Get-ArchiveHash $archive
    [IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS'), "$hash  database.dump`n")
    $manifest = [ordered]@{
        formatVersion = 1; snapshotStartedUtc = $started.ToString('o'); verifiedUtc = [DateTime]::UtcNow.ToString('o')
        sourceDatabase = $database; sourceOwner = $owner; tool = $tool
        bytes = (Get-Item -LiteralPath $archive).Length; sha256 = $hash
    }
    [IO.File]::WriteAllText((Join-Path $stage 'backup.json'), ($manifest | ConvertTo-Json))
    Read-VerifiedBackup $stage | Out-Null
    [IO.Directory]::Move($stage, $destination)
    $stage = $null
    Write-OpsLog $log "BACKUP OK $destination ($($manifest.bytes) bytes) SHA256 $hash"

    $verifiedCount = 0
    foreach ($directory in (Get-BackupDirectories $backupRoot)) {
        try { Read-VerifiedBackup $directory.FullName | Out-Null }
        catch { Write-OpsLog $log "Retained unverified directory $($directory.Name)."; continue }
        $verifiedCount++
        if ($verifiedCount -gt $keep -and $directory.FullName -ine $destination) {
            try {
                Remove-ManagedBackupDirectory $backupRoot $directory.FullName
                Write-OpsLog $log "Rotated $($directory.Name)."
            } catch { Write-OpsLog $log "Rotation warning for $($directory.Name): $($_.Exception.Message)" }
        }
    }
    exit 0
} catch {
    try { Write-OpsLog $log "BACKUP FAILED: $($_.Exception.Message)" }
    catch { Write-Host 'BACKUP FAILED: log destination is unavailable.' }
    exit 1
} finally {
    if ($stage -and (Test-Path -LiteralPath $stage)) {
        try { Remove-ManagedBackupDirectory $backupRoot $stage }
        catch { Write-Warning "Could not remove incomplete staging directory: $stage" }
    }
    if ($lock) { $lock.Dispose() }
}
