param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$signingDir = Join-Path $projectRoot '.signing'
$storeFile = Join-Path $signingDir 'release.jks'
$propertiesFile = Join-Path $signingDir 'signing.properties'
if ((Test-Path -LiteralPath $storeFile) -or (Test-Path -LiteralPath $propertiesFile)) {
    throw 'Signing files already exist. Keep the existing key for updates; do not replace it.'
}
$keytool = if ($JavaHome) { Join-Path $JavaHome 'bin/keytool.exe' } else { 'keytool' }
$randomBytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($randomBytes)
$rng.Dispose()
$password = [Convert]::ToBase64String($randomBytes)
New-Item -ItemType Directory -Path $signingDir -Force | Out-Null
$env:PIXEL_SIGNING_PASSWORD = $password
try {
    & $keytool -genkeypair -keystore $storeFile -storetype JKS -alias pixel-sync -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Pixel Obsidian Sync' -storepass:env PIXEL_SIGNING_PASSWORD -keypass:env PIXEL_SIGNING_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
    $properties = @(
        'RELEASE_STORE_FILE=.signing/release.jks'
        "RELEASE_STORE_PASSWORD=$password"
        'RELEASE_KEY_ALIAS=pixel-sync'
        "RELEASE_KEY_PASSWORD=$password"
    )
    [IO.File]::WriteAllLines($propertiesFile, $properties, [Text.UTF8Encoding]::new($false))
    Write-Host 'Release signing key created in .signing/. Back up this folder securely; it is excluded from Git.'
} finally {
    Remove-Item Env:PIXEL_SIGNING_PASSWORD -ErrorAction SilentlyContinue
}
