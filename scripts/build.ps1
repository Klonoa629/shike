param(
    [switch]$Online,
    [switch]$DeviceTests,
    [string]$SdkPath = 'D:\Software\Android\Sdk',
    [string]$JavaPath = 'D:\Software\Android Studio\jbr'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaExe = Join-Path $JavaPath 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) { throw "JDK not found: $javaExe" }
if (-not (@('platforms\android-37\android.jar', 'platforms\android-37.0\android.jar') |
    Where-Object { Test-Path -LiteralPath (Join-Path $SdkPath $_) })) {
    throw "Android API 37 SDK not found: $SdkPath"
}

# Environment applies only to this build process; no registry or global settings change.
$previousJava = $env:JAVA_HOME
$previousAndroid = $env:ANDROID_HOME
$previousAndroidRoot = $env:ANDROID_SDK_ROOT
$result = 1
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JavaPath
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_SDK_ROOT = $SdkPath
    $buildArgs = @('--no-daemon', '--console=plain')
    if (-not $Online) { $buildArgs += '--offline' }
    $buildArgs += @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
    if ($DeviceTests) { $buildArgs += ':app:assembleDebugAndroidTest' }
    & (Join-Path $projectRoot 'gradlew.bat') @buildArgs
    $result = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousAndroid
    $env:ANDROID_SDK_ROOT = $previousAndroidRoot
    Pop-Location
}
exit $result
