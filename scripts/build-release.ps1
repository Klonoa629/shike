param(
    [switch]$Online,
    [switch]$InitializeSigning,
    [string]$SdkPath = 'D:\Software\Android\Sdk',
    [string]$JavaPath = 'D:\Software\Android Studio\jbr'
)

$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'This helper uses Windows DPAPI. On other platforms configure your own release signing environment.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $projectRoot '.signing'
$signingFile = Join-Path $signingDirectory 'shike-release.p12'
$passwordFile = Join-Path $signingDirectory 'password.dpapi'
$keytool = Join-Path $JavaPath 'bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytool)) { throw "JDK keytool not found: $keytool" }
if (-not (@('platforms\android-37\android.jar', 'platforms\android-37.0\android.jar') |
    Where-Object { Test-Path -LiteralPath (Join-Path $SdkPath $_) })) { throw 'Android SDK Platform 37 is required.' }

$names = @('JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'SHIKE_KEYSTORE_FILE', 'SHIKE_STORE_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$result = 1
Push-Location $projectRoot
try {
    $env:JAVA_HOME = $JavaPath
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_SDK_ROOT = $SdkPath
    $hasKey = Test-Path -LiteralPath $signingFile
    $hasPassword = Test-Path -LiteralPath $passwordFile
    if ($hasKey -ne $hasPassword) { throw 'Signing files are incomplete. Restore the original key/password; do not regenerate a published signing key.' }
    if (-not $hasKey) {
        if (-not $InitializeSigning) { throw 'No release signing key. Use -InitializeSigning once to create a project-local key.' }
        [void][IO.Directory]::CreateDirectory($signingDirectory)
        $env:SHIKE_STORE_PASSWORD = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $protectedPassword = ConvertTo-SecureString -String $env:SHIKE_STORE_PASSWORD -AsPlainText -Force | ConvertFrom-SecureString
        [IO.File]::WriteAllText($passwordFile, $protectedPassword, [Text.UTF8Encoding]::new($false))
        & $keytool -genkeypair -keystore $signingFile -storetype PKCS12 -storepass:env SHIKE_STORE_PASSWORD `
            -keypass:env SHIKE_STORE_PASSWORD -alias shike -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Shike' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed. The saved password was retained for recovery.' }
        Write-Host 'Created project-local signing key. Keep it and a recoverable password backup outside Git.'
    } else {
        $securePassword = Get-Content -Raw -LiteralPath $passwordFile | ConvertTo-SecureString
        $env:SHIKE_STORE_PASSWORD = ConvertFrom-SecureString -SecureString $securePassword -AsPlainText
    }
    $env:SHIKE_KEYSTORE_FILE = $signingFile
    $buildArgs = @('--no-daemon', '--no-configuration-cache', '--console=plain')
    if (-not $Online) { $buildArgs += '--offline' }
    $buildArgs += @(':app:assembleRelease', ':app:testReleaseUnitTest', ':app:lintRelease')
    & (Join-Path $projectRoot 'gradlew.bat') @buildArgs
    $result = $LASTEXITCODE
    if ($result -eq 0) {
        $apk = Join-Path $projectRoot 'app\build\outputs\apk\release\app-release.apk'
        & (Join-Path $SdkPath 'build-tools\36.0.0\apksigner.bat') verify --verbose --print-certs $apk
        $result = $LASTEXITCODE
        if ($result -eq 0) { Get-FileHash -LiteralPath $apk -Algorithm SHA256 }
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    Pop-Location
}
exit $result
