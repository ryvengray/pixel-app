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
Write-Host "Signing secrets configured for $Repository."
