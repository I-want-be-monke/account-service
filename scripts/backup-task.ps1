param(
    [ValidateSet('register', 'run', 'status')][string]$Mode = 'status',
    [string]$Time = '02:00'
)
. "$PSScriptRoot\ops-common.ps1"
$name = 'AccountService-PostgreSQL-Backup'
$taskPath = '\'
$description = "Daily PostgreSQL backup for $RepoRoot"
try {
    $task = Get-ScheduledTask -TaskName $name -TaskPath $taskPath -ErrorAction SilentlyContinue
    if ($task -and $task.Description -cne $description) { throw 'An unrelated task already has this name.' }
    if ($Mode -eq 'register') {
        $at = [DateTime]::ParseExact($Time, 'HH:mm', [Globalization.CultureInfo]::InvariantCulture)
        $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Limited
        $action = New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\cmd.exe') `
            -Argument '/d /c scripts\backup.cmd' -WorkingDirectory $RepoRoot
        $trigger = New-ScheduledTaskTrigger -Daily -At $at
        $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
            -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Hours 1)
        Register-ScheduledTask -TaskName $name -TaskPath $taskPath -Description $description `
            -Principal $principal -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
        Write-Host "Registered $name at $Time for $identity (interactive session, limited rights)."
    } elseif (-not $task) {
        throw "Task is not registered. Run scripts\backup-task.cmd register $Time."
    } elseif ($Mode -eq 'run') {
        Start-ScheduledTask -InputObject $task
        Write-Host 'Backup task started. Run scripts\backup-task.cmd status after it finishes.'
    } else {
        $info = Get-ScheduledTaskInfo -InputObject $task
        Write-Host ('State={0} LastRun={1:yyyy-MM-dd HH:mm:ss} LastResult={2} NextRun={3:yyyy-MM-dd HH:mm:ss}' -f
            $task.State, $info.LastRunTime, $info.LastTaskResult, $info.NextRunTime)
        foreach ($directory in (Get-BackupDirectories (Get-BackupRoot))) {
            try { $last = Read-VerifiedBackup $directory.FullName }
            catch { continue }
            Write-Host "Latest verified backup UTC: $($last.Manifest.verifiedUtc) $($last.Directory)"
            break
        }
    }
    exit 0
} catch {
    Write-Host "BACKUP TASK FAILED: $($_.Exception.Message)"
    exit 1
}
