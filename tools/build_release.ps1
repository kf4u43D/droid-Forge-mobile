param([switch]$RequireSigning, [switch]$Offline, [switch]$SkipBundle)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
function Write-Utf8Report([string]$ReportPath, [string]$ReportText) {
    $reportAbsolutePath = Join-Path (Get-Location).Path $ReportPath
    [System.IO.File]::WriteAllText($reportAbsolutePath, $ReportText + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false))
}
Push-Location -LiteralPath $projectRoot
try {
    $gradleArgs = @('--no-daemon', '--console=plain', ':app:assembleRelease', ':app:lintRelease')
    if (-not $SkipBundle) { $gradleArgs += ':app:bundleRelease' }
    if ($Offline) { $gradleArgs = @('--offline') + $gradleArgs }
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Release compilation or lint failed.' }

    $metadata = Get-Content -LiteralPath 'app/build/outputs/apk/release/output-metadata.json' -Raw | ConvertFrom-Json
    if ($metadata.elements.Count -ne 1) { throw 'Expected one ARM64 APK.' }
    $element = $metadata.elements[0]
    $apk = Join-Path 'app/build/outputs/apk/release' $element.outputFile
    $sdkPath = $env:ANDROID_SDK_ROOT
    if (-not $sdkPath) { $sdkPath = $env:ANDROID_HOME }
    if (-not $sdkPath -and (Test-Path -LiteralPath 'local.properties')) {
        $sdkLine = Get-Content -LiteralPath 'local.properties' | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($sdkLine) { $sdkPath = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    if (-not $sdkPath) { throw 'Set ANDROID_SDK_ROOT or sdk.dir in local.properties to check the APK signature.' }
    $buildTools = Join-Path $sdkPath 'build-tools/36.0.0'
    & (Join-Path $buildTools 'zipalign.exe') -c -P 16 4 $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK ZIP alignment failed.' }
    $savedErrorActionPreference = $ErrorActionPreference
    try {
        # An unsigned review APK is expected to fail apksigner verification.
        # Capture its stderr without converting that expected result into a fatal PowerShell error.
        $ErrorActionPreference = 'Continue'
        $signerOutput = & (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apk 2>&1
        $signed = $LASTEXITCODE -eq 0
    } finally { $ErrorActionPreference = $savedErrorActionPreference }
    $signingInfoRaw = python tools/verify_apk.py $apk --signing-material-only
    if ($LASTEXITCODE -ne 0) { throw 'APK signing structure inspection failed.' }
    $signingInfo = $signingInfoRaw | ConvertFrom-Json
    if (-not $signed -and $signingInfo.present) { throw 'The APK declares signing material but its signature is invalid.' }
    if ($signed -and ($signerOutput -match 'CN=Android Debug')) { throw 'The release APK uses an Android development certificate.' }
    if ($RequireSigning -and -not $signed) { throw 'A public release requires the external production signing configuration.' }

    New-Item -ItemType Directory -Path dist -Force | Out-Null
    $version = $element.versionName
    $suffix = if ($signed) { 'release' } else { 'release-unsigned' }
    $copiedApk = "dist/droid-Forge-mobile-$version-arm64-$suffix.apk"
    Copy-Item -LiteralPath $apk -Destination $copiedApk -Force
    python tools/verify_apk.py $copiedApk --report "dist/release-apk-verification-$version.json"
    if ($LASTEXITCODE -ne 0) { throw 'Release APK verification failed.' }
    if (-not $SkipBundle) {
        $bundle = 'app/build/outputs/bundle/release/app-release.aab'
        $jarSignatureRaw = & java tools/VerifyJarSignature.java $bundle
        if ($LASTEXITCODE -ne 0) { throw 'Invalid AAB JAR signature or incomplete signed-entry coverage.' }
        $jarSignature = $jarSignatureRaw | ConvertFrom-Json
        if ($jarSignature.debug_certificate) { throw 'The release AAB uses an Android development certificate.' }
        if ($RequireSigning -and $jarSignature.status -ne 'signed') { throw 'A public AAB requires a valid JAR signature.' }
        if (($jarSignature.status -eq 'signed') -ne $signed) { throw 'APK and AAB signing states do not match.' }
        if ($signed) {
            $apkCertificates = @($signerOutput | ForEach-Object {
                if ("$_" -match 'certificate SHA-256 digest: ([0-9a-fA-F]+)') { $Matches[1].ToLowerInvariant() }
            })
            if (-not ($jarSignature.certificate_sha256 | Where-Object { $_ -in $apkCertificates })) {
                throw 'APK and AAB signing certificates do not match.'
            }
        }
        $copiedBundle = "dist/droid-Forge-mobile-$version-$suffix.aab"
        Copy-Item -LiteralPath $bundle -Destination $copiedBundle -Force
        Write-Utf8Report "dist/release-aab-signature-$version.json" ($jarSignatureRaw -join [Environment]::NewLine)
        python tools/verify_aab.py $copiedBundle --report "dist/release-aab-verification-$version.json"
        if ($LASTEXITCODE -ne 0) { throw 'Release AAB static verification failed.' }
    }
    python tools/package_source.py "dist/droid-Forge-mobile-$version-source.zip" --apk $copiedApk
    if ($LASTEXITCODE -ne 0) { throw 'Corresponding source packaging failed.' }
    Copy-Item -LiteralPath 'app/build/outputs/mapping/release/mapping.txt' -Destination "dist/droid-Forge-mobile-$version-r8-mapping.txt" -Force
    $symbols = 'app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip'
    if (Test-Path -LiteralPath $symbols) {
        Copy-Item -LiteralPath $symbols -Destination "dist/droid-Forge-mobile-$version-native-symbols.zip" -Force
    }
    $releaseHashes = Get-ChildItem -LiteralPath dist -File | Where-Object { $_.Name -like "droid-Forge-mobile-$version-*" } |
        ForEach-Object { Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 } |
        Select-Object @{Name='File'; Expression={Split-Path -Leaf $_.Path}}, Hash |
        ConvertTo-Json
    Write-Utf8Report "dist/release-sha256-$version.json" $releaseHashes
    Write-Output "Release artifacts prepared. Production signing present: $signed. No publication performed."
} finally {
    Pop-Location
}
