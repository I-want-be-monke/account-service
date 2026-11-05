param(
    [string]$Mode,
    [string]$PackageDirectory,
    [string]$EnvFile,
    [string]$Actor
)

$ErrorActionPreference = 'Stop'
trap {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 2
}
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root
if ($Mode -cnotin 'dry-run', 'apply' -or -not $PackageDirectory -or -not $EnvFile -or -not $Actor) {
    [Console]::Error.WriteLine('Usage: scripts\import-data.cmd dry-run|apply "<package directory>" "<isolated env file>" <accountant login>')
    exit 2
}

function Read-LocalEnv([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line.Trim() -eq '' -or $line.TrimStart().StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
            throw 'Use plain KEY=VALUE lines in the environment file.'
        }
        if ($values.ContainsKey($Matches[1])) { throw 'The environment file contains a duplicate variable.' }
        $values[$Matches[1]] = $Matches[2]
    }
    return $values
}

$workingEnv = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) { throw 'Isolated environment file was not found.' }
$isolatedEnv = (Resolve-Path -LiteralPath $EnvFile).Path
if ($isolatedEnv -ieq $workingEnv) { throw 'Import requires an isolated environment file other than .env.' }
$target = Read-LocalEnv $isolatedEnv
$working = if (Test-Path -LiteralPath $workingEnv -PathType Leaf) { Read-LocalEnv $workingEnv } else { @{} }
if ($target.DB_NAME -cnotmatch '^[a-z_][a-z0-9_]{0,62}$' -or
    $target.DB_APP_USER -cnotmatch '^[a-z_][a-z0-9_]{0,62}$' -or
    [string]::IsNullOrWhiteSpace($target.DB_APP_PASSWORD)) {
    throw 'Set DB_NAME, DB_APP_USER and DB_APP_PASSWORD for the isolated database.'
}
$port = 0
if (-not [int]::TryParse($target.DB_PORT, [ref]$port) -or $port -lt 1 -or $port -gt 65535) {
    throw 'Set DB_PORT to the isolated localhost PostgreSQL port.'
}
if ($target.DB_NAME -in 'accounts', $working.DB_NAME -or $port -in 55432, $working.DB_PORT) {
    throw 'Import requires both an alternate database name and an alternate port.'
}
if ($target.DB_HOST -and $target.DB_HOST -notin '127.0.0.1', 'localhost') {
    throw 'Import supports the isolated PostgreSQL instance on localhost.'
}
if (-not (Test-Path -LiteralPath $PackageDirectory -PathType Container)) { throw 'Import package directory was not found.' }
$package = (Resolve-Path -LiteralPath $PackageDirectory).Path
$jar = Join-Path $root 'target\account-service.jar'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'Build target\account-service.jar before import.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    if (-not $archive.GetEntry('BOOT-INF/classes/application-migration.yml')) {
        throw 'The JAR lacks the migration profile. Rebuild it with mvnw.cmd package.'
    }
} finally {
    $archive.Dispose()
}

$java = 'java.exe'
if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME 'bin\java.exe' }
# The script runs in its own process. Credentials reach Java through its environment only.
$jdbcUrl = "jdbc:postgresql://127.0.0.1:$port/$($target.DB_NAME)"
$env:SPRING_DATASOURCE_USERNAME = $target.DB_APP_USER
$env:SPRING_DATASOURCE_PASSWORD = $target.DB_APP_PASSWORD
& $java '-jar' $jar '--spring.profiles.active=migration' '--spring.main.web-application-type=none' `
    '--spring.flyway.enabled=false' "--spring.datasource.url=$jdbcUrl" `
    "--import.mode=$Mode" "--import.directory=$package" `
    "--import.actor=$Actor" "--import.expected-database=$($target.DB_NAME)"
exit $LASTEXITCODE
