[CmdletBinding()]
param(
    [string]$Compiler = 'C:/Program Files/LLVM/bin/clang++.exe',
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jniRoot = Join-Path $projectRoot '.cache/jni'
$classes = Join-Path $jniRoot 'classes'
$library = Join-Path $jniRoot 'androforge.dll'

if (-not (Test-Path -LiteralPath $Compiler -PathType Leaf)) {
    throw "C++ compiler unavailable: $Compiler"
}
if ([string]::IsNullOrWhiteSpace($Jdk)) {
    throw 'Set JAVA_HOME or pass -Jdk pointing to an installed Windows JDK.'
}
$jdkRoot = [System.IO.Path]::GetFullPath($Jdk)
$javac = Join-Path $jdkRoot 'bin/javac.exe'
$java = Join-Path $jdkRoot 'bin/java.exe'
$jniInclude = Join-Path $jdkRoot 'include'
$jniPlatformInclude = Join-Path $jniInclude 'win32'
foreach ($required in @($javac, $java, (Join-Path $jniInclude 'jni.h'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required JDK file unavailable: $required"
    }
}
New-Item -ItemType Directory -Path $classes -Force | Out-Null

$compileArguments = @(
    '-shared', '-std=c++17', '-O2',
    ('-I' + (Join-Path $projectRoot 'core/include')),
    ('-I' + (Join-Path $projectRoot 'core/generated')),
    ('-I' + $jniInclude), ('-I' + $jniPlatformInclude),
    (Join-Path $projectRoot 'app/src/main/cpp/bridge.cpp'),
    (Join-Path $projectRoot 'core/src/patch_engine.cpp'),
    (Join-Path $projectRoot 'core/src/deployment.cpp'),
    '-o', $library
)
& $Compiler @compileArguments
if ($LASTEXITCODE -ne 0) { throw "JNI host DLL compilation failed ($LASTEXITCODE)." }

& $javac '-encoding' 'UTF-8' '-d' $classes `
    (Join-Path $projectRoot 'app/src/main/java/fr/androforge/NativeBridge.java') `
    (Join-Path $projectRoot 'tools/JniSmokeTest.java')
if ($LASTEXITCODE -ne 0) { throw "JNI smoke Java compilation failed ($LASTEXITCODE)." }

& $java '-Xcheck:jni' ('-Djava.library.path=' + $jniRoot) '-cp' $classes 'fr.androforge.JniSmokeTest'
if ($LASTEXITCODE -ne 0) { throw "JNI host smoke test failed ($LASTEXITCODE)." }
Write-Output "Host JNI DLL: $library"
