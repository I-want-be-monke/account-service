$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $RepoRoot

function Write-OpsLog([string]$Path, [string]$Message) {
    $line = '{0} {1}' -f [DateTime]::UtcNow.ToString('o'), $Message
    if ($Path) { [IO.File]::AppendAllText($Path, $line + [Environment]::NewLine) }
    Write-Host $line
}

function Invoke-Docker {
    param([string[]]$Arguments, [string]$InputFile, [string]$OutputFile, [int]$TimeoutSeconds = 120)
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = (Get-Command docker.exe -ErrorAction Stop).Source
    $start.Arguments = ($Arguments | ForEach-Object {
        '"' + ($_ -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
    }) -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = [bool]$InputFile
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.StandardOutputEncoding = [Text.Encoding]::UTF8
    $start.StandardErrorEncoding = [Text.Encoding]::UTF8
    $inputStream = $null
    $outputStream = $null
    $started = $false
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    try {
        if ($InputFile) { $inputStream = [IO.File]::OpenRead($InputFile) }
        if ($OutputFile) { $outputStream = [IO.File]::Open($OutputFile, 'CreateNew', 'Write', 'None') }
        $started = $process.Start()
        if (-not $started) { throw 'Docker process failed to start.' }
        $stderr = $process.StandardError.ReadToEndAsync()
        $stdout = if ($outputStream) { $process.StandardOutput.BaseStream.CopyToAsync($outputStream) }
            else { $process.StandardOutput.ReadToEndAsync() }
        $send = if ($inputStream) { $inputStream.CopyToAsync($process.StandardInput.BaseStream) }
        $timer = [Diagnostics.Stopwatch]::StartNew()
        do {
            if ($send -and $send.IsCompleted) {
                $process.StandardInput.BaseStream.Close()
                $send = $null
            }
            if ($stdout.IsFaulted) { [void]$stdout.GetAwaiter().GetResult() }
            if ($timer.Elapsed.TotalSeconds -ge $TimeoutSeconds) { throw 'Docker command timed out.' }
        } until ($process.WaitForExit(100))
        $errorOutput = $stderr.GetAwaiter().GetResult().Trim()
        if ($process.ExitCode -ne 0) {
            if ($errorOutput.Length -gt 1800) { $errorOutput = $errorOutput.Substring(0, 1800) }
            throw "Docker command exited $($process.ExitCode). $errorOutput"
        }
        if ($outputStream) { [void]$stdout.GetAwaiter().GetResult(); $outputStream.Flush($true) }
        else { return $stdout.GetAwaiter().GetResult().Trim() }
    } finally {
        if ($started -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
        if ($inputStream) { $inputStream.Dispose() }
        if ($outputStream) { $outputStream.Dispose() }
    }
}

function Get-BackupRoot {
    if ($env:ACCOUNT_BACKUP_ROOT) { return [IO.Path]::GetFullPath($env:ACCOUNT_BACKUP_ROOT) }
    return Join-Path $RepoRoot '.local\backups'
}

function Get-ArchiveHash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha256.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $sha256.Dispose(); $stream.Dispose() }
}

function Read-VerifiedBackup([string]$Directory) {
    $directoryPath = (Resolve-Path -LiteralPath $Directory).ProviderPath
    $archive = Join-Path $directoryPath 'database.dump'
    $checksum = (Get-Content -LiteralPath (Join-Path $directoryPath 'SHA256SUMS') -Raw).Trim()
    $manifest = Get-Content -LiteralPath (Join-Path $directoryPath 'backup.json') -Raw | ConvertFrom-Json
    if ($checksum -cnotmatch '^([0-9a-f]{64})  database\.dump$') { throw 'Invalid SHA256SUMS file.' }
    $expected = $Matches[1]
    $file = Get-Item -LiteralPath $archive
    if ($file.PSIsContainer -or $file.Length -lt 1 -or $file.Length -ne $manifest.bytes -or
        $manifest.formatVersion -ne 1 -or $manifest.sha256 -cne $expected -or
        $manifest.sourceOwner -cnotmatch '^[a-z_][a-z0-9_]{0,62}$' -or
        $manifest.sourceDatabase -cnotmatch '^[a-z_][a-z0-9_]{0,62}$') { throw 'Invalid backup manifest or archive size.' }
    if ((Get-ArchiveHash $archive) -cne $expected) { throw 'Backup SHA-256 mismatch.' }
    return [PSCustomObject]@{ Archive = $archive; Directory = $directoryPath; Manifest = $manifest }
}

function Get-BackupDirectories([string]$Root) {
    if (Test-Path -LiteralPath $Root -PathType Container) {
        Get-ChildItem -LiteralPath $Root -Directory |
            Where-Object { $_.Name -cmatch '^backup-\d{8}T\d{9}Z-[0-9a-f]{12}$' -and
                -not ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) } | Sort-Object Name -Descending
    }
}

function Remove-ManagedBackupDirectory([string]$Root, [string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    $absoluteRoot = (Resolve-Path -LiteralPath $Root).ProviderPath.TrimEnd('\')
    if (-not $item.PSIsContainer -or $item.Parent.FullName -ine $absoluteRoot -or
        $item.Name -cnotmatch '^(backup|stage)-\d{8}T\d{9}Z-[0-9a-f]{12}$' -or
        ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Unexpected backup cleanup path.' }
    $allowedFiles = @('database.dump', 'SHA256SUMS', 'backup.json')
    foreach ($child in (Get-ChildItem -LiteralPath $item.FullName -Force)) {
        if ($child.PSIsContainer -or ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $child.Name -cnotin $allowedFiles) { throw 'Unexpected file in managed backup directory.' }
        Remove-Item -LiteralPath $child.FullName
    }
    Remove-Item -LiteralPath $item.FullName
}
