param(
    [Parameter(Mandatory = $true)][string]$Repository,
    [string]$Gh = 'gh'
)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Use owner/repository.' }
$projectRoot = Split-Path $PSScriptRoot -Parent
$properties = @{}
Get-Content -LiteralPath (Join-Path $projectRoot '.signing/signing.properties') | ForEach-Object {
    $entry = $_.Split('=', 2)
    if ($entry.Length -eq 2) { $properties[$entry[0]] = $entry[1] }
}
$storeFile = Join-Path $projectRoot $properties['RELEASE_STORE_FILE']
$encodedKey = [Convert]::ToBase64String([IO.File]::ReadAllBytes($storeFile))
$encodedKey | & $Gh secret set RELEASE_KEYSTORE_BASE64 --repo $Repository
if ($LASTEXITCODE -ne 0) { throw 'Failed to set keystore secret. Check gh auth status and repository permissions.' }
foreach ($name in @('RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD')) {
    $properties[$name] | & $Gh secret set $name --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw "Failed to set $name" }
}
$firebaseConfig = Join-Path $projectRoot 'app/google-services.json'
if (-not (Test-Path -LiteralPath $firebaseConfig)) { throw 'Missing app/google-services.json. Download it from Firebase Console first.' }
$firebaseBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($firebaseConfig))
$firebaseBase64 | & $Gh secret set FIREBASE_ANDROID_CONFIG_BASE64 --repo $Repository
if ($LASTEXITCODE -ne 0) { throw 'Failed to set FIREBASE_ANDROID_CONFIG_BASE64' }
Write-Host "Signing and Firebase secrets configured for $Repository."
