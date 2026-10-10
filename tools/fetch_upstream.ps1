param([string]$Destination = "")
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
if (-not $Destination) { $Destination = Join-Path $taskRoot 'upstream\droidforge' }
$taskCommit = '60cbb7bbf9630c7d4a184e346a3d1120bde4d69f'
if (-not (Test-Path -LiteralPath (Join-Path $Destination '.git'))) {
    & git -c http.sslBackend=openssl clone --no-checkout https://github.com/Zarkuun/droidforge.git $Destination
    if ($LASTEXITCODE -ne 0) { throw 'Clone Forge failed' }
}
& git -C $Destination checkout --detach $taskCommit
if ($LASTEXITCODE -ne 0) { throw 'Checkout Forge failed' }
