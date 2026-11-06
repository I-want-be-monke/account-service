param(
    [Parameter(Mandatory=$true)][ValidateSet('Capture','Reset','Verify')][string]$Action,
    [string]$EvidenceDirectory = '.local\e11-pilot',
    [ValidatePattern('^(run[0-9]{2}|cold_(charges|payments|debt|movement))$')][string]$ArchiveTag
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\..\scripts\ops-common.ps1')
$project = 'account-service-e11'
$database = 'account_service_e11'
$template = 'account_service_e11_pilot_base'
$next = 'account_service_e11_pilot_next'
$owner = 'account_owner'
$timeout = 1200

function Get-PilotDatabase {
    $container = @(Invoke-Docker -Arguments @('inspect','--type','container',"$project-db-1") | ConvertFrom-Json)
    if ($container.Count -ne 1) { throw 'Expected one dedicated database container.' }
    $db = $container[0]
    $ports = @($db.NetworkSettings.Ports.'5432/tcp')
    $mount = @($db.Mounts | Where-Object Destination -CEQ '/var/lib/postgresql/data')
    if ($db.Id -cnotmatch '^[0-9a-f]{64}$' -or $db.Name -cne "/$project-db-1" -or
        $db.State.Health.Status -cne 'healthy' -or $db.Config.Image -cne 'postgres:17.11-bookworm' -or
        $db.Config.Labels.'com.docker.compose.project' -cne $project -or
        $db.Config.Labels.'com.docker.compose.service' -cne 'db' -or
        $db.Config.Env -cnotcontains "POSTGRES_DB=$database" -or $db.Config.Env -cnotcontains "POSTGRES_USER=$owner" -or
        $ports.Count -ne 1 -or $ports[0].HostIp -cne '127.0.0.1' -or $ports[0].HostPort -cne '55438' -or
        $mount.Count -ne 1 -or $mount[0].Type -cne 'volume' -or $mount[0].Name -cne "${project}_account-data") {
        throw 'Refused target: expected the dedicated E11 database and loopback port.'
    }
    $volume = @(Invoke-Docker -Arguments @('volume','inspect',"${project}_account-data") | ConvertFrom-Json)
    if ($volume.Count -ne 1 -or $volume[0].Labels.'com.docker.compose.project' -cne $project -or
        $volume[0].Labels.'com.docker.compose.volume' -cne 'account-data') {
        throw 'Refused target: E11 data volume labels do not match.'
    }
    return $db.Id
}

function Invoke-Sql([string]$Name, [string]$Sql) {
    Invoke-Docker -Arguments @('exec',$containerId,'psql','-XqAt','--no-password','-v','ON_ERROR_STOP=1',
        '-h','/var/run/postgresql','-U',$owner,'-d',$Name,'-c',$Sql) -TimeoutSeconds $timeout
}

function Assert-Idle([string]$Name) {
    if ((Invoke-Sql 'postgres' "SELECT count(*) FROM pg_stat_activity WHERE datname='$Name' AND backend_type='client backend'") -ne '0') {
        throw "Database $Name still has connected clients."
    }
}

function Get-DatabaseDigest([string]$Name) {
    $temporary = Join-Path $evidence ("digest-" + [Guid]::NewGuid().ToString('N') + '.sql')
    try {
        # The repeatable plain-text stream is hashed and never executed.
        Invoke-Docker -Arguments @('exec',$containerId,'pg_dump','--format=plain','--restrict-key=E11PilotFingerprint',
            '--no-password','-h','/var/run/postgresql','-U',$owner,'-d',$Name) -OutputFile $temporary -TimeoutSeconds $timeout
        return Get-ArchiveHash $temporary
    } finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary }
    }
}

function Assert-DatabaseDigest([string]$Name, [string]$Expected) {
    Assert-Idle $Name
    if ((Get-DatabaseDigest $Name) -cne $Expected) { throw "Database $Name differs from the captured pilot baseline." }
}

function Assert-SnapshotDisk {
    $databaseBytes = [long](Invoke-Sql 'postgres' "SELECT pg_database_size('$database')")
    $freeLine = (Invoke-Docker -Arguments @('exec',$containerId,'df','-Pk','/var/lib/postgresql/data')) -split '\r?\n' |
        Select-Object -Last 1
    $columns = $freeLine.Trim() -split '\s+'
    $hostFree = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($evidence)).AvailableFreeSpace
    if ($columns.Count -lt 6 -or $columns[3] -notmatch '^\d+$' -or
        [long]$columns[3] * 1024L -lt (10GB + $databaseBytes) -or $hostFree -lt (20GB + $databaseBytes)) {
        throw 'Insufficient verified free space to retain the database and create a pilot copy.'
    }
}

try {
    if (($Action -eq 'Reset') -ne [bool]$ArchiveTag) { throw 'Specify -ArchiveTag only for Reset.' }
    $local = Join-Path $RepoRoot '.local'
    $evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
    if (-not $evidence.StartsWith($local + '\',[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence directory must be under repository .local.'
    }
    $cursor = Get-Item -LiteralPath $evidence
    while ($cursor.FullName -ine $RepoRoot) {
        if (-not $cursor.PSIsContainer -or ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Evidence must use real local directories.'
        }
        $cursor = $cursor.Parent
    }
    $manifestPath = Join-Path $evidence 'pilot-baseline.json'
    $archivePath = Join-Path $evidence 'pilot-baseline.dump'
    $catalogPath = Join-Path $evidence 'pilot-baseline.toc.txt'
    $containerId = Get-PilotDatabase
    if ((Invoke-Sql $database "SELECT current_database() || '|' || current_user || '|' || current_setting('port')") -cne
        "$database|$owner|5432") { throw 'Unexpected PostgreSQL connection identity.' }
    $fixture = Invoke-Sql $database "SELECT count(*) FROM audit_event WHERE action='E11_SEED_APPLIED' AND result='SUCCESS' AND details->>'profile'='pilot' AND details->>'accounts'='200000' AND details->>'operations'='1000000'"
    if ($fixture -ne '1') { throw 'A seeded synthetic pilot database is required.' }
    if ($Action -ne 'Verify') { Assert-SnapshotDisk }
    if ($Action -eq 'Capture') {
        if (@($manifestPath,$archivePath,$catalogPath | Where-Object { Test-Path -LiteralPath $_ }).Count -gt 0) {
            throw 'Baseline evidence already exists.'
        }
        if ((Invoke-Sql 'postgres' "SELECT count(*) FROM pg_database WHERE datname IN ('$template','$next')") -ne '0') {
            throw 'A baseline or staging database already exists. Inspect it before proceeding.'
        }
    } else {
        foreach ($artifact in @($manifestPath,$archivePath)) {
            $file = Get-Item -LiteralPath $artifact
            if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw 'Baseline evidence must use regular files.'
            }
        }
        $baseline = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        if ($baseline.database -cne $database -or $baseline.template -cne $template -or
            $baseline.digest -cnotmatch '^[0-9a-f]{64}$' -or $baseline.archiveSha256 -cnotmatch '^[0-9a-f]{64}$' -or
            (Get-ArchiveHash $archivePath) -cne $baseline.archiveSha256) { throw 'Invalid baseline manifest or archive.' }
    }
    if ($Action -eq 'Reset') {
        $previous = "${database}_$ArchiveTag"
        if ((Invoke-Sql 'postgres' "SELECT count(*) FROM pg_database WHERE datname IN ('$previous','$next')") -ne '0') {
            throw 'The archive or staging database already exists. Inspect it before proceeding.'
        }
    }
    $apps = @(Invoke-Docker -Arguments @('ps','--no-trunc','--filter',"label=com.docker.compose.project=$project",
        '--filter','label=com.docker.compose.service=app','--format','{{.ID}}') -split '\r?\n' | Where-Object { $_ })
    foreach ($app in $apps) {
        if ($app -cnotmatch '^[0-9a-f]{64}$') { throw 'Cannot identify the isolated E11 application.' }
        Invoke-Docker -Arguments @('stop',$app) | Out-Null
    }
    Assert-Idle $database
    if ($Action -eq 'Capture') {
        if ((Invoke-Sql $database "SELECT (SELECT count(*) FROM personal_account) || '|' || (SELECT count(*) FILTER (WHERE storage_status='POSTED') || '|' || count(*) FILTER (WHERE storage_status='PREPARED') FROM financial_operation)") -cne '200000|1000000|0') {
            throw 'The baseline requires the original 200000 accounts, 1000000 POSTED operations and no drafts.'
        }
        $digest = Get-DatabaseDigest $database
        Invoke-Docker -Arguments @('exec',$containerId,'pg_dump','-Fc','--no-password','-h','/var/run/postgresql',
            '-U',$owner,'-d',$database) -OutputFile $archivePath -TimeoutSeconds $timeout
        Invoke-Docker -Arguments @('exec','-i',$containerId,'pg_restore','--list') -InputFile $archivePath -OutputFile $catalogPath -TimeoutSeconds $timeout
        Invoke-Sql 'postgres' "CREATE DATABASE $template WITH TEMPLATE $database OWNER $owner" | Out-Null
        Assert-DatabaseDigest $template $digest
        [ordered]@{ capturedAtUtc=[DateTime]::UtcNow.ToString('o'); database=$database; template=$template;
            digest=$digest; archiveSha256=(Get-ArchiveHash $archivePath) } |
            ConvertTo-Json | Set-Content -LiteralPath $manifestPath -Encoding Ascii
    } elseif ($Action -eq 'Reset') {
        Assert-DatabaseDigest $template $baseline.digest
        Invoke-Sql 'postgres' "CREATE DATABASE $next WITH TEMPLATE $template OWNER $owner" | Out-Null
        Assert-DatabaseDigest $next $baseline.digest
        Invoke-Sql 'postgres' "BEGIN; ALTER DATABASE $database RENAME TO $previous; ALTER DATABASE $next RENAME TO $database; COMMIT" | Out-Null
        Write-Host "Previous database retained as $previous."
    } else {
        Assert-DatabaseDigest $database $baseline.digest
    }
    if ($Action -ne 'Capture') { $digest = $baseline.digest }
    Write-Host "$([DateTime]::UtcNow.ToString('o')) $Action content SHA-256 $digest"
    Write-Host 'Pilot snapshot verified. E11 applications remain stopped.'
    exit 0
} catch {
    Write-Host "PILOT SNAPSHOT FAILED: $($_.Exception.Message)"
    exit 1
}
