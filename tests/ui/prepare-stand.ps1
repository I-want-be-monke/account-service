param([string]$OpenSsl)
$ErrorActionPreference = 'Stop'
if (-not $OpenSsl) {
    $gitOpenSsl = Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe'
    $OpenSsl = if (Test-Path -LiteralPath $gitOpenSsl) { $gitOpenSsl } else { 'openssl.exe' }
}
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $root
$project = 'account-service-e11'
$database = 'account_service_e11'
$settingsPath = Join-Path $root '.local\e11\stand.env'
$private = Join-Path $root '.local\e11-continuation\ui-tls'
$envValues = @{}
foreach ($line in (Get-Content -LiteralPath $settingsPath)) {
    if ($line -cmatch '^([A-Z_]+)=(.+)$') { $envValues[$Matches[1]] = $Matches[2] }
}
if ($envValues.DB_NAME -cne $database -or $envValues.DB_PORT -cne '55438' -or
    $envValues.HTTP_PORT -cne '18048' -or $envValues.HTTPS_PORT -cne '18448' -or
    $envValues.TLS_PASSWORD -cnotmatch '^[a-f0-9]{48}$') { throw 'Expected the isolated E11 settings.' }
foreach ($key in $envValues.Keys) { Set-Item "Env:$key" $envValues[$key] }

function Assert-IsolatedDatabase {
    $container = "$project-db-1"
    $rawLabels = & docker inspect --format '{{json .Config.Labels}}' $container
    if ($LASTEXITCODE -ne 0) { throw 'Start the seeded E11 stand first.' }
    $labels = ConvertFrom-Json $rawLabels
    if ($labels.'com.docker.compose.project' -cne $project -or
        $labels.'com.docker.compose.service' -cne 'db') { throw 'Unexpected E11 database container.' }
    $port = & docker port $container 5432/tcp
    if ($LASTEXITCODE -ne 0 -or $port -cne '127.0.0.1:55438') { throw 'Unexpected E11 database port.' }
    $actual = & docker exec $container psql -XAt --no-password -U account_owner -d $database -c 'select current_database()'
    if ($LASTEXITCODE -ne 0 -or $actual -cne $database) { throw 'Unexpected E11 database.' }
}

function Invoke-OpenSsl([string[]]$Arguments) {
    $ErrorActionPreference = 'Continue'
    $output = & $OpenSsl @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw ('OpenSSL failed: ' + $Arguments[0] + ' ' + ($output -join ' ')) }
}

try {
    Assert-IsolatedDatabase
    [IO.Directory]::CreateDirectory($private) | Out-Null
    $ca = Join-Path $private 'ca.pem'
    $server = Join-Path $private 'server.pem'
    $store = Join-Path $private 'server.p12'
    $pin = Join-Path $private 'spki.txt'
    if (-not (Test-Path -LiteralPath $store)) {
        $caKey = Join-Path $private 'ca.key'
        $serverKey = Join-Path $private 'server.key'
        $csr = Join-Path $private 'server.csr'
        $extensions = Join-Path $private 'server.ext'
        [IO.File]::WriteAllLines($extensions, @('basicConstraints=critical,CA:FALSE',
            'keyUsage=critical,digitalSignature,keyEncipherment','extendedKeyUsage=serverAuth',
            'subjectAltName=DNS:localhost,IP:127.0.0.1','authorityKeyIdentifier=keyid,issuer'), [Text.Encoding]::ASCII)
        try {
            Invoke-OpenSsl @('req','-x509','-newkey','rsa:3072','-nodes','-sha256','-days','7',
                '-subj','/CN=E11 UI Local Test CA','-addext','basicConstraints=critical,CA:TRUE,pathlen:0',
                '-addext','keyUsage=critical,keyCertSign,cRLSign','-keyout',$caKey,'-out',$ca)
            Invoke-OpenSsl @('req','-new','-newkey','rsa:3072','-nodes','-sha256','-subj','/CN=localhost',
                '-keyout',$serverKey,'-out',$csr)
            Invoke-OpenSsl @('x509','-req','-in',$csr,'-CA',$ca,'-CAkey',$caKey,'-CAcreateserial',
                '-sha256','-days','7','-extfile',$extensions,'-out',$server)
            Invoke-OpenSsl @('pkcs12','-export','-inkey',$serverKey,'-in',$server,'-certfile',$ca,
                '-name','accounts-local','-passout','env:TLS_PASSWORD','-out',$store)
        } finally {
            Remove-Item -LiteralPath $caKey,$serverKey -ErrorAction SilentlyContinue
        }
    }
    Invoke-OpenSsl @('verify','-verify_hostname','localhost','-CAfile',$ca,$server)
    Invoke-OpenSsl @('x509','-in',$server,'-checkend','3600','-noout')
    $publicPem = Join-Path $private 'server-public.pem'
    $publicDer = Join-Path $private 'server-public.der'
    Invoke-OpenSsl @('x509','-in',$server,'-noout','-pubkey','-out',$publicPem)
    Invoke-OpenSsl @('pkey','-pubin','-in',$publicPem,'-outform','DER','-out',$publicDer)
    $hash = [Security.Cryptography.SHA256]::Create()
    try { [IO.File]::WriteAllText($pin, [Convert]::ToBase64String($hash.ComputeHash([IO.File]::ReadAllBytes($publicDer)))) }
    finally { $hash.Dispose() }
    $env:APP_TLS_FILE = $store
    $env:E11_UI_CA_FILE = $ca
    & docker compose -p $project --env-file $settingsPath -f compose.yaml -f tests/performance/compose.e11.yaml `
        -f tests/ui/compose.ui.yaml --profile full up -d --no-deps --build --wait --wait-timeout 160 app
    if ($LASTEXITCODE -ne 0) { throw 'Isolated UI app did not start.' }
    # Schannel has no revocation endpoint for this short-lived private CA.
    & curl.exe --ssl-revoke-best-effort --cacert $ca --fail --silent --show-error 'https://localhost:18448/actuator/health/readiness'
    if ($LASTEXITCODE -ne 0) { throw 'The isolated UI server certificate or readiness check failed.' }
    Write-Host "`nE11 UI server ready. CA: $ca  SPKI: $pin"
    exit 0
} catch {
    Write-Host "UI STAND FAILED: $($_.Exception.Message)"
    exit 1
}
