[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [string]$Sdk = 'C:/Android/SDK',
    [string]$NdkVersion = '28.2.13676358',
    [switch]$IncludeOfficialExamples,
    [switch]$OfficialExamplesOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$outputRoot = Join-Path $projectRoot '.cache/tablet-native'
$compiler = Join-Path $Sdk "ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang++.cmd"
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
foreach ($required in @($compiler, $adb)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing Android tool: $required" }
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

# This exercises the same engine tests on ARM64, independently of UI state.
# No application file, patch, or Android setting is changed.
$remoteRoot = '/data/local/tmp/androforge-native-' + [Guid]::NewGuid().ToString('N')
$targets = [System.Collections.Generic.List[object]]::new()
if (-not $OfficialExamplesOnly) {
    $targets.Add(@('tests', 'core/tests/patch_engine_test.cpp'))
    $targets.Add(@('deployment-tests', 'core/tests/deployment_test.cpp'))
    $targets.Add(@('benchmark', 'core/tests/patch_engine_benchmark.cpp'))
}
$corpusRoot = Join-Path $projectRoot 'resources/official/examples/patches'
$corpusFiles = @()
$remoteCorpus = "$remoteRoot/corpus"
if ($IncludeOfficialExamples -or $OfficialExamplesOnly) {
    if (-not (Test-Path -LiteralPath $corpusRoot -PathType Container)) { throw 'Official example corpus is missing.' }
    $corpusFiles = @(Get-ChildItem -LiteralPath $corpusRoot -File -Filter '*.ini' | Sort-Object Name)
    if ($corpusFiles.Count -ne 630) { throw 'Expected the complete 630-entry official corpus.' }
    foreach ($file in $corpusFiles) {
        if ($file.Name -cnotmatch '^[A-Za-z0-9_-]+\.ini$') { throw "Unsafe official example filename: $($file.Name)" }
    }
    # The native runner enumerates the directory; transfer only this validated flat file set.
    $targets.Add(@('official-examples', 'core/tests/official_examples_test.cpp'))
}
foreach ($target in $targets) {
    $executable = Join-Path $outputRoot $target[0]
    & $compiler '-std=c++17' '-O2' '-fPIE' '-pie' '-static-libstdc++' '-Wall' '-Wextra' '-Wpedantic' `
        ('-I' + (Join-Path $projectRoot 'core/include')) `
        ('-I' + (Join-Path $projectRoot 'core/generated')) `
        (Join-Path $projectRoot 'core/src/patch_engine.cpp') `
        (Join-Path $projectRoot 'core/src/deployment.cpp') `
        (Join-Path $projectRoot $target[1]) '-o' $executable
    if ($LASTEXITCODE -ne 0) { throw "ARM64 $($target[0]) compilation failed ($LASTEXITCODE)." }
}
& $adb '-s' $DeviceSerial 'shell' 'mkdir' $remoteRoot
if ($LASTEXITCODE -ne 0) { throw 'Device unavailable; native tests did not run.' }
try {
    if ($corpusFiles.Count) {
        & $adb '-s' $DeviceSerial 'shell' 'mkdir' $remoteCorpus
        if ($LASTEXITCODE -ne 0) { throw 'Failed to create private official corpus directory.' }
        # Batches avoid command-line limits and leave exact known cleanup paths even on partial failure.
        for ($offset = 0; $offset -lt $corpusFiles.Count; $offset += 32) {
            $batch = @($corpusFiles | Select-Object -Skip $offset -First 32 | ForEach-Object { $_.FullName })
            & $adb '-s' $DeviceSerial 'push' @batch "$remoteCorpus/"
            if ($LASTEXITCODE -ne 0) { throw 'Failed to transfer the complete official corpus.' }
        }
    }
    foreach ($target in $targets) {
        $remoteExecutable = "$remoteRoot/$($target[0])"
        & $adb '-s' $DeviceSerial 'push' (Join-Path $outputRoot $target[0]) $remoteExecutable
        if ($LASTEXITCODE -ne 0) { throw 'Failed to transfer native test executable.' }
        & $adb '-s' $DeviceSerial 'shell' 'chmod' '700' $remoteExecutable
        if ($LASTEXITCODE -ne 0) { throw 'Failed to prepare native executable.' }
        if ($target[0] -eq 'official-examples') {
            & $adb '-s' $DeviceSerial 'shell' $remoteExecutable $remoteCorpus
        } else {
            & $adb '-s' $DeviceSerial 'shell' $remoteExecutable
        }
        if ($LASTEXITCODE -ne 0) { throw "ARM64 $($target[0]) failed ($LASTEXITCODE)." }
    }
} finally {
    # Remove only exact files owned by this run, including any interrupted push; never recurse.
    foreach ($target in $targets) {
        & $adb '-s' $DeviceSerial 'shell' 'rm' '-f' "$remoteRoot/$($target[0])"
    }
    if ($corpusFiles.Count) {
        for ($offset = 0; $offset -lt $corpusFiles.Count; $offset += 32) {
            $batch = @($corpusFiles | Select-Object -Skip $offset -First 32 | ForEach-Object { "$remoteCorpus/$($_.Name)" })
            & $adb '-s' $DeviceSerial 'shell' 'rm' '-f' @batch
        }
        & $adb '-s' $DeviceSerial 'shell' 'rmdir' $remoteCorpus
    }
    & $adb '-s' $DeviceSerial 'shell' 'rmdir' $remoteRoot
}
