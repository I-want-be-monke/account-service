param(
    [Parameter(Mandatory=$true)][ValidateSet('Seed','Reconcile')][string]$Command,
    [ValidateSet('pilot','reduced','smoke')][string]$Profile = 'reduced',
    [ValidateRange(1,999999999)][int]$Seed = 1101,
    [ValidatePattern('^[0-9]{4}-[0-9]{2}$')][string]$ReportMonth = '2026-10',
    [ValidatePattern('^[A-Za-z0-9_-]{1,40}$')][string]$Label = 'before',
    [string]$EvidenceDirectory = '.local\e11-verification'
)
$ErrorActionPreference = 'Stop'
$e11Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $e11Root 'scripts\ops-common.ps1')
$project = 'account-service-e11'
$database = 'account_service_e11'
$containerName = "$project-db-1"
$volumeName = "${project}_account-data"
$guardSql = "SET accounts.e11_dedicated = 'account-service-e11'"

function Get-E11Stand {
    $inspect = @(Invoke-Docker -Arguments @('inspect','--type','container',$containerName) | ConvertFrom-Json)
    if ($inspect.Count -ne 1) { throw 'Expected one E11 database container.' }
    $container = $inspect[0]
    $labels = $container.Config.Labels
    $ports = @($container.NetworkSettings.Ports.'5432/tcp')
    $dataMounts = @($container.Mounts | Where-Object Destination -CEQ '/var/lib/postgresql/data')
    if ($container.Id -cnotmatch '^[0-9a-f]{64}$' -or $container.Name -cne "/$containerName" -or
        $container.State.Running -ne $true -or $container.State.Health.Status -cne 'healthy' -or
        $labels.'com.docker.compose.project' -cne $project -or $labels.'com.docker.compose.service' -cne 'db' -or
        $labels.'com.docker.compose.container-number' -cne '1' -or
        $ports.Count -ne 1 -or $ports[0].HostIp -cne '127.0.0.1' -or $ports[0].HostPort -cne '55438' -or
        $dataMounts.Count -ne 1 -or $dataMounts[0].Type -cne 'volume' -or $dataMounts[0].Name -cne $volumeName -or
        $container.Config.Image -cne 'postgres:17.11-bookworm') {
        throw 'Refused target: expected the healthy dedicated E11 Compose database, volume and loopback port 55438.'
    }
    $volume = @(Invoke-Docker -Arguments @('volume','inspect',$volumeName) | ConvertFrom-Json)
    if ($volume.Count -ne 1 -or $volume[0].Name -cne $volumeName -or
        $volume[0].Labels.'com.docker.compose.project' -cne $project -or
        $volume[0].Labels.'com.docker.compose.volume' -cne 'account-data') {
        throw 'Refused target: E11 data volume labels do not match.'
    }
    $dbEntries = @($container.Config.Env | Where-Object { $_ -cmatch '^POSTGRES_DB=' })
    $ownerEntries = @($container.Config.Env | Where-Object { $_ -cmatch '^POSTGRES_USER=' })
    if ($dbEntries.Count -ne 1 -or $dbEntries[0] -cne "POSTGRES_DB=$database" -or
        $ownerEntries.Count -ne 1 -or $ownerEntries[0] -cnotmatch '^POSTGRES_USER=([a-z_][a-z0-9_]{0,62})$') {
        throw 'Refused target: E11 database identity or owner is invalid.'
    }
    $owner = $Matches[1]
    # Subsequent commands use the immutable ID rather than a mutable container name.
    $psql = @('exec','-i',$container.Id,'psql','-X','-qAt','-v','ON_ERROR_STOP=1',
        '-h','/var/run/postgresql','-U',$owner,'-d',$database)
    $identity = Invoke-Docker -Arguments ($psql + @('-c',"select current_database()||'|'||current_setting('port')"))
    if ($identity -cne "$database|5432") { throw 'Refused target: PostgreSQL connection identity does not match.' }
    return [PSCustomObject]@{ Id=$container.Id; Image=$container.Config.Image; Psql=$psql }
}

function Get-E11Disk($Stand) {
    $hostFree = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($e11Root)).AvailableFreeSpace
    $df = Invoke-Docker -Arguments @('exec',$Stand.Id,'df','-Pk','/var/lib/postgresql/data')
    $columns = (($df -split '\r?\n') | Select-Object -Last 1).Trim() -split '\s+'
    if ($columns.Count -lt 6 -or $columns[3] -notmatch '^[0-9]+$') { throw 'Cannot measure Docker database disk space.' }
    return [ordered]@{ workspaceDriveFreeBytes=[long]$hostFree; dockerDataFreeBytes=([long]$columns[3]*1024L) }
}

function Save-E11Artifact([string]$Path,[string]$Text) {
    $stream = [IO.File]::Open($Path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text + [Environment]::NewLine)
        $stream.Write($bytes,0,$bytes.Length)
    } finally { $stream.Dispose() }
}

function Invoke-E11Sql($Stand,[string]$File,[string[]]$Variables,[int]$TimeoutSeconds) {
    $args = $Stand.Psql + @('-v',"reportMonth=$ReportMonth-01") + $Variables + @('-c',$guardSql,'-f','-')
    return Invoke-Docker -Arguments $args -InputFile (Join-Path $PSScriptRoot $File) -TimeoutSeconds $TimeoutSeconds
}

function Assert-E11Checks($Check) {
    if ($Check.database -cne $database -or $null -eq $Check.fixture) {
        throw 'E11 reconciliation expected a completed seed in the dedicated database.'
    }
    $failures = @($Check.violations.PSObject.Properties | Where-Object { [long]$_.Value -ne 0 })
    if ($failures.Count -gt 0) { throw ('E11 reconciliation failed: ' + (($failures | ForEach-Object Name) -join ', ')) }
}

try {
    if ($Command -eq 'Seed' -and $Profile -eq 'pilot') {
        $reported = Invoke-Docker -Arguments @('info','--format','{{.NCPU}}|{{.MemTotal}}') -TimeoutSeconds 20
        $resources = [regex]::Match($reported, '^([0-9]+)\|([0-9]+)$')
        if (-not $resources.Success) { throw 'Cannot determine effective Docker CPU and memory for the pilot.' }
        $vcpu = [long]$resources.Groups[1].Value
        $memory = [long]$resources.Groups[2].Value
        if ($vcpu -lt 4 -or $memory -lt 8000000000L) {
            throw "Pilot requires Docker with at least 4 vCPU and 8000000000 bytes of memory. Actual: $vcpu vCPU, $memory bytes."
        }
    }
    $local = [IO.Path]::GetFullPath((Join-Path $e11Root '.local'))
    $evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
    if (-not $evidence.StartsWith(($local.TrimEnd('\') + '\'),[StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence directory must be inside the repository .local directory.'
    }
    $cursor = $evidence
    while ($cursor.Length -ge $local.Length) {
        if ((Test-Path -LiteralPath $cursor) -and ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Evidence directory cannot use a junction or symbolic link.'
        }
        $cursor = Split-Path -Parent $cursor
    }
    $null = New-Item -ItemType Directory -Path $evidence -Force
    $stand = Get-E11Stand
    if ($Command -eq 'Reconcile') {
        $path = Join-Path $evidence "reconciliation-$Label.json"
        if (Test-Path -LiteralPath $path) { throw 'Choose a new reconciliation label to retain previous evidence.' }
        $raw = Invoke-E11Sql $stand 'reconcile.sql' @() 660
        $checked = $raw | ConvertFrom-Json
        Save-E11Artifact $path $raw
        Assert-E11Checks $checked
        Write-Host "FIN-09 and independent report reconciliation passed: $path"
        exit 0
    }

    $seedFile = Join-Path $evidence 'seed.json'
    $fixtureFile = Join-Path $evidence 'fixtures.csv'
    if ((Test-Path -LiteralPath $seedFile) -or (Test-Path -LiteralPath $fixtureFile)) {
        throw 'Seed evidence already exists. The generator does not replace evidence or reseed the database.'
    }
    $before = Get-E11Disk $stand
    if ($before.workspaceDriveFreeBytes -lt 20GB -or $before.dockerDataFreeBytes -lt 10GB) {
        throw "E11 seed stopped before writes. Requires 20 GiB free on the workspace drive and 10 GiB on Docker data. Actual bytes: $($before.workspaceDriveFreeBytes), $($before.dockerDataFreeBytes)."
    }
    $sizes = switch ($Profile) {
        'pilot'   { @{ accounts=200000; clients=150000; operations=1000000 } }
        'reduced' { @{ accounts=50000; clients=37500; operations=250000 } }
        'smoke'   { @{ accounts=1200; clients=900; operations=6000 } }
    }
    $start = [DateTime]::UtcNow
    $timer = [Diagnostics.Stopwatch]::StartNew()
    Write-Host "Generating E11 $Profile data in $database at 127.0.0.1:55438. Accounts: $($sizes.accounts). Posted operations: $($sizes.operations)."
    $null = Invoke-E11Sql $stand 'seed.sql' @('-v',"profile=$Profile",'-v',"seed=$Seed",
        '-v',"accounts=$($sizes.accounts)",'-v',"clients=$($sizes.clients)",'-v',"operations=$($sizes.operations)") 1800
    $timer.Stop()
    $raw = Invoke-E11Sql $stand 'reconcile.sql' @() 660
    $checked = $raw | ConvertFrom-Json
    $after = Get-E11Disk $stand
    $metadata = [ordered]@{
        startedAtUtc=$start.ToString('o'); verifiedAtUtc=[DateTime]::UtcNow.ToString('o')
        seedSeconds=[Math]::Round($timer.Elapsed.TotalSeconds,3); composeProject=$project
        databaseContainerId=$stand.Id; databaseVolume=$volumeName; image=$stand.Image
        hostBinding='127.0.0.1:55438'; seed=$Seed; profile=$Profile; reportMonth=$ReportMonth
        diskBefore=$before; diskAfter=$after; reconciliation=$checked
    }
    Save-E11Artifact $seedFile ($metadata | ConvertTo-Json -Depth 12)
    Assert-E11Checks $checked
    $fixtures = Invoke-E11Sql $stand 'fixtures.sql' @() 60
    if (@($fixtures | ConvertFrom-Csv).Count -ne 300) { throw 'Expected 300 valid E11 workload fixture accounts.' }
    Save-E11Artifact $fixtureFile $fixtures
    Write-Host "E11 seed validated in $($metadata.seedSeconds)s. Evidence: $seedFile. Fixtures: $fixtureFile."
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
