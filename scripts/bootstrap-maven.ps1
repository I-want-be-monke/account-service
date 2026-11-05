$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$settings = @{}
Get-Content (Join-Path $root '.mvn\wrapper\maven-wrapper.properties') | ForEach-Object {
    $pair = $_ -split '=', 2
    if ($pair.Count -eq 2) { $settings[$pair[0]] = $pair[1] }
}
$destination = Join-Path $root '.mvn\dists'
$zip = Join-Path $destination 'maven.zip'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $settings.distributionUrl -OutFile $zip -UseBasicParsing
    $actual = (Get-FileHash -Path $zip -Algorithm SHA512).Hash
    if ($actual -ine $settings.distributionSha512Sum) { throw 'Maven archive checksum mismatch' }
    Expand-Archive -Path $zip -DestinationPath $destination -Force
} finally {
    Remove-Item -LiteralPath $zip -ErrorAction SilentlyContinue
}
