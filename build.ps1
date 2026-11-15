param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$appRoot = $PSScriptRoot
if (-not $AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $JavaHome) { throw 'Set JAVA_HOME or pass -JavaHome.' }
$pythonExe = (Get-Command $Python -ErrorAction Stop).Source
$platform = Join-Path $AndroidSdk 'platforms/android-35/android.jar'
$tools = Join-Path $AndroidSdk 'build-tools/35.0.0'
if (-not (Test-Path -LiteralPath $platform)) { throw 'Install Android SDK Platform 35.' }
if (-not (Test-Path -LiteralPath "$tools/aapt2.exe")) { throw 'Install Android SDK Build-Tools 35.0.0.' }
$build = Join-Path $appRoot build
$signing = Join-Path $appRoot signing
$keystore = Join-Path $signing map.p12
$secretFile = Join-Path $signing password.dpapi
$javaBin = Join-Path $JavaHome bin

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program returned $LASTEXITCODE" }
}

New-Item -ItemType Directory -Force -Path $build,"$build/classes","$build/dex",$signing | Out-Null
Invoke-Checked "$tools/aapt2.exe" @('compile', '--dir', "$appRoot/res", '-o', "$build/resources.zip")
Invoke-Checked "$tools/aapt2.exe" @('link', '-I', $platform, '--manifest', "$appRoot/AndroidManifest.xml",
    '-A', "$appRoot/assets", '-o', "$build/unsigned.apk", "$build/resources.zip")
Invoke-Checked "$javaBin/javac.exe" @('--release', '8', '-encoding', 'UTF-8', '-classpath', $platform,
    '-d', "$build/classes", "$appRoot/MainActivity.java")
Invoke-Checked "$javaBin/jar.exe" @('cf', "$build/classes.jar", '-C', "$build/classes", '.')
Invoke-Checked "$javaBin/java.exe" @('-cp', "$tools/lib/d8.jar", 'com.android.tools.r8.D8',
    '--release', '--min-api', '26', '--lib', $platform, '--output', "$build/dex", "$build/classes.jar")
Invoke-Checked $pythonExe @('-c',
    'import sys, zipfile; z=zipfile.ZipFile(sys.argv[1], "a", compression=zipfile.ZIP_DEFLATED); z.write(sys.argv[2], "classes.dex"); z.close()',
    "$build/unsigned.apk", "$build/dex/classes.dex")
Invoke-Checked "$tools/zipalign.exe" @('-f', '4', "$build/unsigned.apk", "$build/aligned.apk")

if (Test-Path -LiteralPath $keystore) {
    $protected = (Get-Content -Raw -LiteralPath $secretFile).Trim() | ConvertTo-SecureString
    $env:SAMARA_APK_SIGNING_PASSWORD = [System.Net.NetworkCredential]::new('', $protected).Password
} else {
    $env:SAMARA_APK_SIGNING_PASSWORD = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    $protected = ConvertTo-SecureString $env:SAMARA_APK_SIGNING_PASSWORD -AsPlainText -Force
    $protected | ConvertFrom-SecureString | Set-Content -LiteralPath $secretFile
}
try {
    if (-not (Test-Path -LiteralPath $keystore)) {
        Invoke-Checked "$javaBin/keytool.exe" @('-genkeypair', '-keystore', $keystore, '-storetype', 'PKCS12',
            '-storepass:env', 'SAMARA_APK_SIGNING_PASSWORD', '-alias', 'samara', '-keyalg', 'RSA',
            '-keysize', '2048', '-validity', '10950', '-dname', 'CN=Samara Cameras')
    }
    Invoke-Checked "$javaBin/java.exe" @('-jar', "$tools/lib/apksigner.jar", 'sign',
        '--ks', $keystore, '--ks-pass', 'env:SAMARA_APK_SIGNING_PASSWORD',
        '--v4-signing-enabled', 'false', '--out', "$build/samara-cameras.apk", "$build/aligned.apk")
} finally {
    Remove-Item Env:SAMARA_APK_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
Invoke-Checked "$javaBin/java.exe" @('-jar', "$tools/lib/apksigner.jar", 'verify', '--verbose', "$build/samara-cameras.apk")
Invoke-Checked "$tools/zipalign.exe" @('-c', '4', "$build/samara-cameras.apk")
Invoke-Checked "$tools/aapt2.exe" @('dump', 'badging', "$build/samara-cameras.apk")
