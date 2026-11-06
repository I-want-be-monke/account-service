param(
    [Parameter(Mandatory=$true)][string]$EvidenceDirectory,
    [Parameter(Mandatory=$true)][ValidateSet('01','02','03')][string]$Run
)
$ErrorActionPreference = 'Stop'

function Read-Properties([string]$Path) {
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -notmatch '^([^=]+)=(.*)$' -or $result.ContainsKey($Matches[1])) {
            throw "Invalid evidence property in $Path"
        }
        $result[$Matches[1]] = $Matches[2]
    }
    return $result
}

function Measure-Requests($Rows, [double]$Seconds) {
    $samples = @($Rows)
    if ($samples.Count -eq 0) { return [ordered]@{ samples=0 } }
    $times = @($samples | ForEach-Object { [long]$_.elapsed } | Sort-Object)
    return [ordered]@{
        samples=$times.Count; errors=@($samples | Where-Object success -CNE 'true').Count
        throughput=[Math]::Round($times.Count/$Seconds, 3)
        meanMs=[Math]::Round(($times | Measure-Object -Average).Average, 3)
        p50Ms=$times[[int][Math]::Ceiling($times.Count*0.50)-1]
        p95Ms=$times[[int][Math]::Ceiling($times.Count*0.95)-1]
        p99Ms=$times[[int][Math]::Ceiling($times.Count*0.99)-1]
        maxMs=$times[-1]
    }
}

function Same-Report($Expected, $Actual) {
    $columns = @($Expected.PSObject.Properties)
    if ($columns.Count -eq 0 -or $columns.Count -ne @($Actual.PSObject.Properties).Count) { return $false }
    foreach ($column in $columns) {
        $other = $Actual.PSObject.Properties[$column.Name]
        if ($null -eq $other -or [decimal]$column.Value -ne [decimal]$other.Value) { return $false }
    }
    return $true
}

try {
    $local = Join-Path (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path '.local'
    $root = (Resolve-Path -LiteralPath $EvidenceDirectory).Path
    if (-not $root.StartsWith($local+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence must be under .local.' }
    for ($dir=$root; $dir.Length -ge $local.Length; $dir=(Split-Path -Parent $dir)) {
        if ((Get-Item -LiteralPath $dir -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Evidence path cannot use a junction or symbolic link.'
        }
    }
    $load = Join-Path $root "load-$Run"
    $runPath = Join-Path $load 'run-01'
    $profile = Read-Properties (Join-Path $load 'profile.properties')
    $state = Read-Properties (Join-Path $runPath 'run.properties')
    $before = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root "reconciliation-before-$Run.json") | ConvertFrom-Json
    $after = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $root "reconciliation-after-$Run.json") | ConvertFrom-Json
    $requests = @(Import-Csv -Encoding UTF8 -LiteralPath (Join-Path $runPath 'raw-results.jtl'))
    $scenarios = @(Import-Csv -Encoding UTF8 -LiteralPath (Join-Path $runPath 'scenario-results.csv'))
    $totals = @(Import-Csv -Encoding UTF8 -LiteralPath (Join-Path $runPath 'report-totals.csv'))
    $ledger = @(Import-Csv -Encoding UTF8 -LiteralPath (Join-Path $root "ledger-after-$Run.csv"))
    $resources = @(Import-Csv -Encoding UTF8 -LiteralPath (Join-Path $runPath 'telemetry.csv'))
    $metrics = @(Get-Content -Encoding UTF8 -LiteralPath (Join-Path $runPath 'actuator-metrics.jsonl') |
        ForEach-Object { $_ | ConvertFrom-Json })
    $issues = [Collections.Generic.List[string]]::new()
    function Check([bool]$Condition, [string]$Message) { if (-not $Condition) { $issues.Add($Message) } }

    $windowStart = [long]$state.steadyStartEpochMs
    $windowEnd = [long]$state.steadyEndEpochMs
    $month = $profile.reportMonth
    Check ($profile.project -ceq 'account-service-e11' -and $profile.database -ceq 'account_service_e11' -and
        $profile.url -ceq 'https://localhost:18448' -and $state.runId -cmatch '^[a-f0-9]{8}-[a-f0-9]{3}$') 'Unexpected stand or run ID.'
    Check ($profile.users -eq '300' -and $profile.runs -eq '1' -and $profile.rampSeconds -eq '300' -and
        $profile.warmupSeconds -eq '300' -and $profile.steadySeconds -eq '1200' -and $profile.reportTailSeconds -eq '0' -and
        $windowStart -gt 0 -and ($windowEnd-$windowStart) -eq 1200000) 'Unexpected pilot profile or timing window.'
    Check ($before.fixture.profile -ceq 'pilot' -and [int]$before.fixture.seed -eq [int]$profile.seed -and
        [int]$before.counts.syntheticClients -eq 150000 -and [int]$before.counts.accounts -eq 200000 -and
        [int]$before.counts.syntheticUsers -eq 300 -and
        [int]$after.counts.accounts -eq 200000 -and [int]$profile.accounts -eq 200000 -and
        [long]$before.counts.posted -ge 1000000 -and [long]$profile.posted -eq [long]$before.counts.posted -and
        @($before.postedByMonth.PSObject.Properties).Count -eq 12) 'Pilot baseline counts are missing or incorrect.'
    $correctionShare = 100*[double]$before.counts.corrections/[Math]::Max(1,[long]$before.counts.posted)
    Check ($correctionShare -ge 4 -and $correctionShare -le 6 -and $before.counts.charges -gt 0 -and $before.counts.payments -gt 0 -and
        @('open','suspended','closed','zero','debt','advance' | Where-Object { $before.accountDistribution.$_ -le 0 }).Count -eq 0) 'Pilot seed is missing the required kinds, balances or account statuses.'
    foreach ($snapshot in @($before,$after)) {
        $checks = @($snapshot.violations.PSObject.Properties)
        Check ($snapshot.database -ceq 'account_service_e11' -and $snapshot.reportMonth -ceq $month -and
            $checks.Count -eq 8 -and @($checks | Where-Object Value -NE 0).Count -eq 0) 'FIN-09 or database/month identity failed.'
    }
    $monthPosted = $before.postedByMonth.PSObject.Properties[$month]
    Check ($null -ne $monthPosted -and [long]$monthPosted.Value -eq [long]$profile.monthPosted -and
        [long]$monthPosted.Value -eq ($before.postedByMonth.PSObject.Properties.Value | Measure-Object -Maximum).Maximum) 'Report month is not the confirmed busiest baseline month.'

    $httpErrors = @($requests | Where-Object success -CNE 'true')
    $serverErrors = @($requests | Where-Object responseCode -Match '^5\d\d$')
    $failedScenarios = @($scenarios | Where-Object success -CNE 'true')
    Check ($requests.Count -gt 0 -and $httpErrors.Count -eq 0 -and $serverErrors.Count -eq 0 -and
        $failedScenarios.Count -eq 0 -and @($requests | Where-Object URL -CNotLike 'https://localhost:18448/*').Count -eq 0) 'Unexpected HTTP, telemetry or scenario errors, or non-E11 HTTPS destination.'
    $steady = @($requests | Where-Object phase -CEQ 'STEADY')
    Check (@($steady | Where-Object { [long]$_.timeStamp -lt $windowStart -or [long]$_.timeStamp -ge $windowEnd }).Count -eq 0) 'STEADY samples outside the recorded window.'
    $logins = @($requests | Where-Object { $_.label -ceq 'POST /login' -and $_.success -ceq 'true' })
    $loginNames = @($logins.threadName | Sort-Object -Unique)
    $activeNames = @($steady | Where-Object { $_.category -in 'view','search','finance','statement','report' -and $_.success -ceq 'true' } |
        Select-Object -ExpandProperty threadName -Unique)
    Check ($logins.Count -eq 300 -and $loginNames.Count -eq 300 -and $activeNames.Count -eq 300 -and
        @($activeNames | Where-Object { $_ -notin $loginNames }).Count -eq 0 -and
        [int]$state.uniqueAuthenticatedSessions -eq 300 -and [int]$state.readyBySteady -eq 300 -and
        [int]$state.steadyUsersWithSuccessfulRequests -eq 300 -and $state.leftBeforeSteadyEnd -eq '0') '300 distinct authenticated users did not stay active for STEADY.'
    $roles = [ordered]@{ ADMIN=2; OPERATOR=60; ACCOUNTANT=40; MANAGER=18; CLIENT=180 }
    foreach ($role in $roles.Keys) {
        $prefix = '^e11-' + $role.ToLowerInvariant() + '-\d{3}$'
        Check (@($loginNames | Where-Object { $_ -cmatch $prefix }).Count -eq $roles[$role]) "Unexpected number of $role users."
    }

    $traffic = @($steady | Where-Object { $_.category -in 'view','search','finance','statement' })
    $screen = @($traffic | Where-Object label -CLike 'GET *')
    $screenStats = Measure-Requests $screen 1200
    Check ($screenStats.samples -gt 0 -and $screenStats.p95Ms -le 2000) 'Combined screen GET p95 exceeds 2000 ms or is missing.'
    $routes = [ordered]@{}
    foreach ($route in @('GET /accounts/{id}','GET /my/accounts','GET /accounts/{id}/operations',
        'GET /clients?accountNumber','GET /accounts?number','GET /accounts/{id}/statement/result',
        'GET /accounts/{id}/operations/new','GET /operations/{id}/confirm')) {
        $stat = Measure-Requests @($screen | Where-Object label -CEQ $route) 1200
        $routes[$route] = $stat
        Check ($stat.samples -gt 0 -and $stat.p95Ms -le 2000) "Screen route p95 exceeds 2000 ms or is missing: $route"
    }
    $mix = [ordered]@{}
    $target = @{view=60;search=20;finance=10;statement=10}
    foreach ($category in 'view','search','finance','statement') {
        $samples = @($traffic | Where-Object category -CEQ $category).Count
        $share = [Math]::Round(100*$samples/[Math]::Max(1,$traffic.Count), 2)
        $mix[$category] = [ordered]@{samples=$samples;sharePercent=$share}
        Check ([Math]::Abs($share-$target[$category]) -le 5) "Measured request mix differs by more than five percentage points: $category"
    }

    $reports = [ordered]@{}
    $reportRequests = @($requests | Where-Object category -CEQ 'report')
    $events = @(
        foreach ($request in $reportRequests) {
            # The JTL start is rounded down and elapsed is rounded up.
            $start = [long]$request.timeStamp + 1
            $finish = [long]$request.timeStamp + [long]$request.elapsed - 1
            if ($finish -gt $start) {
                [PSCustomObject]@{Time=$start;Delta=1}
                [PSCustomObject]@{Time=$finish;Delta=-1}
            }
        }
    )
    $inFlight = 0; $peak = 0
    foreach ($event in $events | Sort-Object Time,Delta) {
        $inFlight += $event.Delta
        $peak = [Math]::Max($peak, $inFlight)
    }
    Check ($peak -eq 5 -and [int]$state.maxParallelReports -eq 5) 'Expected evidence of five parallel reports.'
    $verifiedTotals = 0
    $reportTypes = @('CHARGES','PAYMENTS','DEBT','MOVEMENT')
    Check (@($reportRequests | Where-Object { $_.label -notin ($reportTypes | ForEach-Object { "GET /reports/result $_" }) }).Count -eq 0 -and
        @($totals | Where-Object type -CNotIn $reportTypes).Count -eq 0) 'Unexpected report type.'
    foreach ($kind in $reportTypes) {
        $samples = @($reportRequests | Where-Object label -CEQ "GET /reports/result $kind")
        $complete = @($samples | Where-Object { $_.phase -ceq 'STEADY' -and $_.success -ceq 'true' -and
            ([long]$_.timeStamp+[long]$_.elapsed) -le ($windowEnd+1) })
        $stat = Measure-Requests $samples 1200
        $stat.completedInSteady = $complete.Count
        $reports[$kind] = $stat
        Check ($complete.Count -ge 30 -and $complete.Count -eq $samples.Count -and $stat.maxMs -le 10000 -and
            $complete.Count -eq [int]$state["steadyCompletedReports.$kind"]) "Report $kind missed count, stable window or 10-second limit."
        $expected = $before.reports.$kind
        Check (Same-Report $expected $after.reports.$kind) "Report SQL totals changed during the run: $kind"
        $groups = @($totals | Where-Object type -CEQ $kind | Group-Object ordinal)
        Check ($groups.Count -eq $complete.Count) "Report $kind has incomplete totals evidence."
        $observed = @{}
        foreach ($sample in $complete) { $observed[$sample.timeStamp + '|' + $sample.threadName] = $true }
        foreach ($group in $groups) {
            $actual = [ordered]@{}
            $first = $group.Group[0]
            $key = $first.timeStamp + '|' + $first.login
            foreach ($row in $group.Group) { $actual[$row.column] = $row.amount }
            Check ($actual.Count -eq $group.Count -and @($group.Group | Where-Object phase -CNE 'STEADY').Count -eq 0 -and
                $observed.ContainsKey($key) -and @($group.Group | Where-Object { $_.timeStamp -ne $first.timeStamp -or $_.login -ne $first.login }).Count -eq 0 -and
                (Same-Report $expected ([PSCustomObject]$actual))) "Report totals differ from SQL: $kind #$($group.Name)"
            $observed.Remove($key)
            $verifiedTotals += $actual.Count
        }
    }

    $confirmations = @($requests | Where-Object { $_.label -ceq 'POST /operations/{id}/confirm' -and $_.success -ceq 'true' })
    $financialScenarios = @($scenarios | Where-Object { $_.action -ceq 'finance' -and $_.success -ceq 'true' })
    $count = $confirmations.Count
    Check ($count -gt 0 -and $count -eq $ledger.Count -and $count -eq $financialScenarios.Count -and
        $count -eq [long]$state.successfulFinancialActions -and
        $count -eq ([long]$after.counts.posted-[long]$before.counts.posted) -and
        $count -eq ([long]$after.counts.postingSuccesses-[long]$before.counts.postingSuccesses) -and
        $after.counts.prepared -eq $before.counts.prepared -and $after.counts.corrections -eq $before.counts.corrections -and
        $after.counts.deniedAudits -eq $before.counts.deniedAudits -and $after.counts.failedAudits -eq $before.counts.failedAudits) 'Financial or audit counts differ.'
    $operations = @{}
    $kinds = @{ CHARGE=0; PAYMENT=0 }
    foreach ($row in $ledger) {
        $validKind = $kinds.ContainsKey($row.kind)
        if ($validKind) { $kinds[$row.kind]++ }
        Check (-not $operations.ContainsKey($row.operation_id) -and $validKind -and $row.storage_status -ceq 'POSTED' -and
            $row.audit_action -ceq 'OPERATION_POSTED' -and $row.audit_result -ceq 'SUCCESS' -and
            $row.audit_document -ceq $row.external_number -and [decimal]$row.audit_delta -eq [decimal]$row.delta -and
            (($row.kind -ceq 'CHARGE' -and [decimal]$row.delta -eq 0.01) -or
             ($row.kind -ceq 'PAYMENT' -and [decimal]$row.delta -eq -0.01))) "Invalid ledger/audit row: $($row.operation_id)"
        $operations[$row.operation_id] = $row
    }
    Check ($kinds.CHARGE -eq ([long]$after.counts.charges-[long]$before.counts.charges) -and
        $kinds.PAYMENT -eq ([long]$after.counts.payments-[long]$before.counts.payments)) 'Posted operation types do not match SQL counts.'
    foreach ($request in $confirmations) {
        $uri = [uri]$request.URL
        if ($uri.AbsolutePath -cnotmatch '^/operations/([0-9a-f-]{36})/confirm$') {
            Check $false 'Financial confirmation URL does not identify an operation.'
            continue
        }
        $id = $Matches[1]
        $row = $operations[$id]
        Check ($null -ne $row -and $request.requestId -match '^[0-9a-f-]{36}$' -and $row.request_id -eq $request.requestId -and
            $row.posted_by -ceq $request.threadName -and $row.audit_actor -ceq $request.threadName) "Confirmation did not match exactly one SQL audit entry: $id"
        $operations.Remove($id)
    }
    Check ($operations.Count -eq 0) 'Unmatched SQL postings remain.'

    $steadyResources = @($resources | Where-Object phase -CEQ 'STEADY')
    $dbSamples = @($steadyResources | Where-Object { $_.pgActivity.StartsWith('{') -and
        $_.containerStats -match 'account-service-e11-app-1' -and $_.containerStats -match 'account-service-e11-db-1' })
    $metricNames = @($metrics | Where-Object { $null -ne $_.payload } | Select-Object -ExpandProperty metric -Unique)
    Check ($dbSamples.Count -ge 2 -and @($steadyResources | Where-Object { [int]$_.activeUsers -ne 300 }).Count -eq 0 -and
        @($steadyResources | Where-Object { $_.readiness -notmatch '^200\|\{\s*"status"\s*:\s*"UP"\s*\}$' }).Count -eq 0 -and
        @('hikaricp.connections.active','hikaricp.connections.pending','hikaricp.connections.acquire',
        'jvm.memory.used?tag=area:heap' | Where-Object { $_ -notin $metricNames }).Count -eq 0) 'Missing pilot telemetry or sampled active-user count.'

    $review = [ordered]@{
        run=$Run; runId=$state.runId; checkedAtUtc=[DateTime]::UtcNow.ToString('o'); reportMonth=$month
        status=if ($issues.Count) { 'FAIL' } else { 'PASS' }
        issues=@($issues.ToArray()); samples=$requests.Count; unexpectedErrors=$httpErrors.Count; http5xx=$serverErrors.Count
        steadyUsers=$activeNames.Count; steadyScreen=$screenStats; steadyRoutes=$routes; steadyRequestMix=$mix
        reports=$reports; peakReportOverlap=$peak; verifiedReportCells=$verifiedTotals
        financial=[ordered]@{ confirmations=$count; charges=$kinds.CHARGE; payments=$kinds.PAYMENT;
            before=$before.counts; after=$after.counts; violations=$after.violations }
        telemetry=[ordered]@{ steadySamples=$steadyResources.Count; completeContainerAndDbSamples=$dbSamples.Count;
            metricNames=$metricNames }
    }
    $output = Join-Path $root "review-$Run.json"
    $stream = [IO.File]::Open($output,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($review | ConvertTo-Json -Depth 12) + [Environment]::NewLine)
        $stream.Write($bytes,0,$bytes.Length)
    } finally { $stream.Dispose() }
    Write-Host "Run $Run evidence: $($review.status). $output"
    if ($issues.Count) { exit 2 }
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
