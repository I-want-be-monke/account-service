$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
$project = 'account-service-defense'
$envPath = Join-Path $root '.local\defense.env'
$tlsPath = Join-Path $root '.local\dev.p12'
$volumeName = "${project}_account-data"
$fixed = [ordered]@{
    DB_NAME = 'account_service_defense'
    DB_PORT = '55439'
    HTTP_PORT = '18050'
    HTTPS_PORT = '18450'
    DB_OWNER_USER = 'account_owner'
    DB_APP_USER = 'account_app'
    APP_PROFILE = 'dev'
}
$secretKeys = @('DB_OWNER_PASSWORD', 'DB_APP_PASSWORD', 'DEMO_ADMIN_PASSWORD', 'DEMO_OPERATOR_PASSWORD',
    'DEMO_ACCOUNTANT_PASSWORD', 'DEMO_MANAGER_PASSWORD', 'DEMO_CLIENT_PASSWORD')

function Read-Settings([string]$Path) {
    $settings = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) { continue }
        if ($line -cnotmatch '^([A-Z_]+)=(.*)$' -or $settings.ContainsKey($Matches[1])) {
            throw 'Invalid local settings format. Use unique KEY=VALUE lines.'
        }
        $settings[$Matches[1]] = $Matches[2]
    }
    return $settings
}

function New-Secret {
    $bytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) }
    finally { $random.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Get-DockerJson([string[]]$Arguments) {
    $result = & docker @Arguments 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Docker inspection failed. Check the local image and stand identity.' }
    return ($result -join "`n") | ConvertFrom-Json
}

function Assert-Port($Container, [string]$ContainerPort, [string]$HostPort) {
    $bindings = @($Container.HostConfig.PortBindings.$ContainerPort)
    if ($bindings.Count -ne 1 -or $bindings[0].HostIp -cne '127.0.0.1' -or
        $bindings[0].HostPort -cne $HostPort) {
        throw 'Existing defense container has unexpected port bindings.'
    }
}

function Assert-Stand($Settings, [bool]$RequireEmpty) {
    $known = @(& docker ps -a --filter "label=com.docker.compose.project=$project" --format '{{.Names}}')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the defense project.' }
    if (@($known | Where-Object { $_ -cnotin @("$project-app-1", "$project-db-1") }).Count -gt 0) {
        throw 'Defense project has unexpected containers.'
    }
    if ($RequireEmpty -and $known.Count -gt 0) {
        throw 'Existing defense containers require their original defense.env.'
    }
    foreach ($service in @('db', 'app')) {
        $name = "$project-$service-1"
        $exists = & docker ps -a -q --filter "name=^/$name`$"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the defense container name.' }
        if (-not $exists) { continue }
        $container = @(Get-DockerJson @('inspect', '--type', 'container', $name))[0]
        $labels = $container.Config.Labels
        if ($container.Name -cne "/$name" -or $labels.'com.docker.compose.project' -cne $project -or
            $labels.'com.docker.compose.service' -cne $service -or
            $labels.'com.docker.compose.container-number' -cne '1') {
            throw 'Existing defense container identity does not match.'
        }
        $environment = @{}
        foreach ($entry in $container.Config.Env) {
            $split = $entry.IndexOf('=')
            if ($split -gt 0) { $environment[$entry.Substring(0, $split)] = $entry.Substring($split + 1) }
        }
        if ($service -eq 'db') {
            if (@($container.HostConfig.PortBindings.PSObject.Properties).Count -ne 1) {
                throw 'Existing defense database has unexpected published ports.'
            }
            Assert-Port $container '5432/tcp' $Settings.DB_PORT
            $data = @($container.Mounts | Where-Object Destination -ceq '/var/lib/postgresql/data')
            if ($data.Count -ne 1 -or $data[0].Type -cne 'volume' -or $data[0].Name -cne $volumeName -or
                $environment.POSTGRES_DB -cne $Settings.DB_NAME -or
                $environment.POSTGRES_USER -cne $Settings.DB_OWNER_USER -or
                $environment.POSTGRES_PASSWORD -cne $Settings.DB_OWNER_PASSWORD -or
                $environment.ACCOUNT_DB_PASSWORD -cne $Settings.DB_APP_PASSWORD) {
                throw 'Existing defense database configuration differs from defense.env.'
            }
        } else {
            if (@($container.HostConfig.PortBindings.PSObject.Properties).Count -ne 2) {
                throw 'Existing defense app has unexpected published ports.'
            }
            Assert-Port $container "$($Settings.HTTPS_PORT)/tcp" $Settings.HTTPS_PORT
            Assert-Port $container "$($Settings.HTTP_PORT)/tcp" $Settings.HTTP_PORT
            if ($environment.DB_HOST -cne 'db' -or
                $environment.DB_NAME -cne $Settings.DB_NAME -or $environment.DB_PORT -cne '5432') {
                throw 'Existing defense app configuration differs from the selected database.'
            }
        }
    }
    $volume = & docker volume ls -q --filter "name=^$volumeName`$"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the defense volume.' }
    if ($volume) {
        if ($RequireEmpty) { throw 'Existing defense data requires its original defense.env.' }
        $metadata = @(Get-DockerJson @('volume', 'inspect', $volumeName))[0]
        if ($metadata.Name -cne $volumeName -or $metadata.Labels.'com.docker.compose.project' -cne $project -or
            $metadata.Labels.'com.docker.compose.volume' -cne 'account-data') {
            throw 'Existing defense volume belongs to another configuration.'
        }
        $users = @(& docker ps -a --filter "volume=$volumeName" --format '{{.Names}}')
        if ($LASTEXITCODE -ne 0 -or @($users | Where-Object { $_ -cne "$project-db-1" }).Count -gt 0) {
            throw 'Defense volume is referenced by an unexpected container.'
        }
    }
    $network = & docker network ls -q --filter "name=^${project}_default`$"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the defense network.' }
    if ($network) {
        $metadata = @(Get-DockerJson @('network', 'inspect', "${project}_default"))[0]
        if ($metadata.Labels.'com.docker.compose.project' -cne $project -or
            $metadata.Labels.'com.docker.compose.network' -cne 'default') {
            throw 'Existing defense network belongs to another configuration.'
        }
    }
}

try {
    if (-not (Test-Path -LiteralPath $tlsPath -PathType Leaf)) { throw 'Existing .local\dev.p12 is required.' }
    $source = Read-Settings (Join-Path $root '.env')
    if ($source.TLS_PASSWORD -cnotmatch '^[0-9a-f]{48}$') { throw 'Expected the existing generated TLS_PASSWORD in .env.' }
    & docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is unavailable.' }
    $newSettings = -not (Test-Path -LiteralPath $envPath)
    if (-not $newSettings) {
        if (-not (Test-Path -LiteralPath $envPath -PathType Leaf)) { throw 'defense.env must be a file.' }
        $settings = Read-Settings $envPath
    } else {
        $settings = @{}
        foreach ($key in $fixed.Keys) { $settings[$key] = $fixed[$key] }
        foreach ($key in $secretKeys) { $settings[$key] = New-Secret }
        $settings.TLS_PASSWORD = $source.TLS_PASSWORD
    }
    foreach ($key in $fixed.Keys) {
        if ($settings[$key] -cne $fixed[$key]) { throw "Defense setting $key must match its dedicated configuration." }
    }
    foreach ($key in $secretKeys) {
        if ($settings[$key] -cnotmatch '^[0-9a-f]{48}$') { throw "Invalid generated defense secret $key." }
    }
    if ($settings.TLS_PASSWORD -cne $source.TLS_PASSWORD) {
        throw 'Defense TLS settings do not match the existing certificate settings.'
    }
    $expectedKeys = @($fixed.Keys) + $secretKeys + @('TLS_PASSWORD')
    if ($settings.Count -ne $expectedKeys.Count) { throw 'Unexpected keys in defense.env.' }
    Assert-Stand $settings $newSettings
    if ($newSettings) {
        $lines = @($expectedKeys | ForEach-Object { "$_=$($settings[$_])" })
        $stream = [IO.File]::Open($envPath, 'CreateNew', 'Write', 'None')
        $writer = New-Object IO.StreamWriter($stream, [Text.Encoding]::ASCII)
        try { foreach ($line in $lines) { $writer.WriteLine($line) } }
        finally { $writer.Dispose(); $stream.Dispose() }
        Write-Host 'Prepared .local\defense.env with independent database and demo passwords.'
    } else {
        Write-Host 'Reused existing .local\defense.env.'
    }
    Write-Host 'Defense settings ready. Database account_service_defense, ports 55439 / 18050 / 18450.'
    exit 0
} catch {
    [Console]::Error.WriteLine("DEMO FAILED: $($_.Exception.Message)")
    exit 1
}
