[CmdletBinding()]
param([string]$Jdk = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($Jdk)) { throw 'Set JAVA_HOME or pass -Jdk for an installed JDK.' }
$javac = Join-Path $Jdk 'bin/javac.exe'
$java = Join-Path $Jdk 'bin/java.exe'
foreach ($required in @($javac, $java)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required JDK tool unavailable: $required" }
}
$classes = Join-Path $projectRoot '.cache/module-geometry/classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& $javac '-encoding' 'UTF-8' '-d' $classes `
    (Join-Path $projectRoot 'app/src/main/java/fr/androforge/ModuleGeometry.java') `
    (Join-Path $projectRoot 'tools/ModuleGeometryTest.java')
if ($LASTEXITCODE -ne 0) { throw "Module geometry compilation failed ($LASTEXITCODE)." }
& $java '-cp' $classes 'fr.androforge.ModuleGeometryTest'
if ($LASTEXITCODE -ne 0) { throw "Module geometry regression failed ($LASTEXITCODE)." }
