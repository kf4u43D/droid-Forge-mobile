[CmdletBinding()]
param([string]$Jdk = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($Jdk)) { throw 'Set JAVA_HOME or pass -Jdk pointing to JDK 17 or newer.' }
$jdkRoot = [System.IO.Path]::GetFullPath($Jdk)
$javac = Join-Path $jdkRoot 'bin/javac.exe'
$java = Join-Path $jdkRoot 'bin/java.exe'
foreach ($required in @($javac, $java)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required JDK tool unavailable: $required" }
}
$classes = Join-Path $projectRoot '.cache/droid-operation-gate/classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& $javac '--release' '17' '-encoding' 'UTF-8' '-d' $classes `
    (Join-Path $projectRoot 'app/src/main/java/fr/androforge/DroidOperationGate.java') `
    (Join-Path $projectRoot 'tools/DroidOperationGateTest.java')
if ($LASTEXITCODE -ne 0) { throw "DROID operation gate compilation failed ($LASTEXITCODE)." }
& $java '-Xmx256m' '-cp' $classes 'fr.androforge.DroidOperationGateTest'
if ($LASTEXITCODE -ne 0) { throw "DROID operation gate regression failed ($LASTEXITCODE)." }
