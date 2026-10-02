$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Push-Location $project
try {
    if (!(Test-Path -LiteralPath 'signing.properties')) { & "$PSScriptRoot\Initialize-Signing.ps1" }
    & .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease :app:assembleDebugAndroidTest --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Build or validation failed.' }
    New-Item -ItemType Directory -Force -Path 'dist' | Out-Null
    $apk = Join-Path $project 'dist\earbud-companion-0.1.0-preview.apk'
    Copy-Item -LiteralPath 'app\build\outputs\apk\release\app-release.apk' -Destination $apk
    $hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText((Join-Path $project 'dist\SHA256SUMS.txt'), "$hash  $([IO.Path]::GetFileName($apk))`n", [Text.UTF8Encoding]::new($false))
    Write-Output "Signed APK: $apk"
    Write-Output "SHA256: $hash"
} finally { Pop-Location }
