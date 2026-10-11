[CmdletBinding()]
param([string]$Jdk = $env:JAVA_HOME, [string]$Python = 'python')

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($Jdk)) { throw 'Set JAVA_HOME or pass -Jdk pointing to JDK 17 or newer.' }
$jdkRoot = [System.IO.Path]::GetFullPath($Jdk)
$javac = Join-Path $jdkRoot 'bin/javac.exe'
$java = Join-Path $jdkRoot 'bin/java.exe'
foreach ($required in @($javac, $java)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required JDK file unavailable: $required" }
}
$testDir = Join-Path $projectRoot '.cache/example-text'
$classes = Join-Path $testDir 'classes'
$fixture = Join-Path $testDir 'metadata.tsv'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$makeFixture = @'
import base64, hashlib, json, pathlib, sys
source, destination = map(pathlib.Path, sys.argv[1:])
original = source.read_bytes()
entries = json.loads(original)['examples']
rows = ['\t'.join(base64.b64encode(item.get(key, '').encode('utf-8')).decode('ascii')
                  for key in ('id', 'category', 'title', 'note')) for item in entries]
destination.write_text('\n'.join(rows) + '\n', encoding='ascii')
assert source.read_bytes() == original, 'The official manifest must remain byte exact'
assert hashlib.sha256(original).hexdigest() == '30b246488c8b98200a193879941fca3f0347c0e9e108760087e87e4cc01ee4e3'
'@
& $Python '-B' '-c' $makeFixture (Join-Path $projectRoot 'app/src/main/assets/examples/manifest.json') $fixture
if ($LASTEXITCODE -ne 0) { throw "Example metadata fixture generation failed ($LASTEXITCODE)." }
& $javac '--release' '17' '-encoding' 'UTF-8' '-d' $classes `
    (Join-Path $projectRoot 'app/src/main/java/fr/androforge/ExampleText.java') `
    (Join-Path $projectRoot 'tools/ExampleTextTest.java')
if ($LASTEXITCODE -ne 0) { throw "Example display test compilation failed ($LASTEXITCODE)." }
& $java '-cp' $classes 'fr.androforge.ExampleTextTest' $fixture
if ($LASTEXITCODE -ne 0) { throw "Example display tests failed ($LASTEXITCODE)." }
