param([string]$JavaHome = $env:JAVA_HOME, [string]$Gradle = '.\gradlew.bat')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
Push-Location $projectRoot
try {
    & $Gradle --no-daemon assembleRelease
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
    $version = (Get-Content app/version.properties | Where-Object { $_ -like 'VERSION_NAME=*' }).Split('=', 2)[1]
    New-Item -ItemType Directory -Path dist -Force | Out-Null
    $apk = "pixel-obsidian-sync-$version.apk"
    Copy-Item -LiteralPath app/build/outputs/apk/release/app-release.apk -Destination "dist/$apk"
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath "dist/$apk").Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $projectRoot "dist/$apk.sha256"), "$hash  $apk`n", [Text.UTF8Encoding]::new($false))
    Write-Host "Signed APK: dist/$apk"
} finally { Pop-Location }
