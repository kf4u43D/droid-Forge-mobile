[CmdletBinding()]
param([string]$Jdk = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$classes = Join-Path $projectRoot '.cache/block-transitions/classes'
if ([string]::IsNullOrWhiteSpace($Jdk)) { throw 'Set JAVA_HOME or pass -Jdk pointing to a Windows JDK 17 or newer.' }
$jdkRoot = [System.IO.Path]::GetFullPath($Jdk)
$javac = Join-Path $jdkRoot 'bin/javac.exe'
$java = Join-Path $jdkRoot 'bin/java.exe'
foreach ($required in @($javac, $java)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required JDK file unavailable: $required" }
}
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& $javac '--release' '17' '-encoding' 'UTF-8' '-d' $classes `
    (Join-Path $projectRoot 'app/src/main/java/fr/androforge/BlockTransitions.java') `
    (Join-Path $projectRoot 'tools/BlockTransitionsTest.java')
if ($LASTEXITCODE -ne 0) { throw "Block transition test compilation failed ($LASTEXITCODE)." }
& $java '-cp' $classes 'fr.androforge.BlockTransitionsTest'
if ($LASTEXITCODE -ne 0) { throw "Block transition test failed ($LASTEXITCODE)." }
