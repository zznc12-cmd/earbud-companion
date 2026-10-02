param([string]$Repository = 'earbud-companion')
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$buildConfig = [IO.File]::ReadAllText((Join-Path $project 'app\build.gradle.kts'))
$version = [regex]::Match($buildConfig, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (!$version) { throw 'versionName is missing.' }
$apk = Join-Path $project "dist\earbud-companion-$version.apk"
$hashFile = Join-Path $project 'dist\SHA256SUMS.txt'
$releaseNotes = Join-Path $project 'docs\RELEASE-NOTES.md'
if (!(Test-Path -LiteralPath $apk) -or !(Test-Path -LiteralPath $hashFile)) { throw 'Run Build-Release.ps1 before publishing.' }
$expectedHash = ([IO.File]::ReadAllText($hashFile) -split '\s+')[0]
if ((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) { throw 'APK checksum does not match.' }
$env:GIT_TERMINAL_PROMPT = '0'; $env:GCM_INTERACTIVE = 'Never'
$credential = @('protocol=https','host=github.com','') | git credential fill 2>$null
$line = $credential | Where-Object { $_ -like 'password=*' } | Select-Object -First 1
if (!$line) { throw 'No authenticated GitHub credential. Sign in with Git Credential Manager first.' }
$headers = @{ Authorization = "Bearer $($line.Substring(9))"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
function Api($Method, $Url, $Data = $null) {
    if ($null -ne $Data) {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($Data | ConvertTo-Json -Depth 10))
        Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $bytes
    } else { Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers }
}
$owner = (Api 'GET' 'https://api.github.com/user').login
$repoUrl = "https://api.github.com/repos/$owner/$Repository"
try { $repo = Api 'GET' $repoUrl } catch {
    if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
    $repo = Api 'POST' 'https://api.github.com/user/repos' @{
        name = $Repository; description = 'Offline Android headphone notification reader — hardware-unverified preview'; private = $false; auto_init = $false
    }
}
if ($repo.private) { throw 'The download repository is private; phone downloads would require authentication. No visibility change was made.' }
Push-Location $project
try {
    if (!(Test-Path -LiteralPath '.git')) { git init -b main | Out-Null; if ($LASTEXITCODE -ne 0) { throw 'Git initialization failed.' } }
    git add -- .
    if ($LASTEXITCODE -ne 0) { throw 'Staging failed.' }
    $forbidden = git ls-files -- 'signing.properties' '*.jks' '*.keystore' 'local.properties' 'dist/*'
    if ($forbidden) { throw 'Private signing/configuration or generated release files were staged. Publishing stopped.' }
    git diff --cached --quiet
    if ($LASTEXITCODE -eq 1) {
        git -c "user.name=$owner" -c "user.email=$owner@users.noreply.github.com" commit -m "Update Earbud Companion $version"
        if ($LASTEXITCODE -ne 0) { throw 'Git commit failed.' }
    }
    $origin = if ('origin' -in @(git remote)) { git remote get-url origin } else { $null }
    if (!$origin) { git remote add origin $repo.clone_url }
    elseif ($origin -ne $repo.clone_url) { throw 'Existing origin points to another repository; it was not changed.' }
    git push -u origin main
    if ($LASTEXITCODE -ne 0) { throw 'Git push failed; no release was created.' }
    $tag = "v$version"
    try { $existing = Api 'GET' "$repoUrl/releases/tags/$tag" } catch {
        if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
        $existing = $null
    }
    if ($existing -and !$existing.draft) { throw 'This release is already published. Bump versionCode and versionName rather than replace the existing APK.' }
    $body = [IO.File]::ReadAllText($releaseNotes, [Text.Encoding]::UTF8)
    $release = if ($existing) { $existing } else {
        Api 'POST' "$repoUrl/releases" @{
            tag_name = $tag; target_commitish = 'main'; name = "Earbud Companion $version"; body = $body; draft = $true; prerelease = $true
        }
    }
    foreach ($file in @($apk, $hashFile)) {
        $name = [IO.Path]::GetFileName($file)
        if ($release.assets.name -contains $name) { throw "Draft asset $name already exists; do not overwrite it blindly." }
        $upload = ($release.upload_url -replace '\{.*$', '') + '?name=' + [Uri]::EscapeDataString($name)
        $asset = Invoke-RestMethod -Method Post -Uri $upload -Headers $headers -ContentType 'application/octet-stream' -InFile $file
        if ($asset.state -ne 'uploaded') { throw 'Asset upload did not complete.' }
    }
    $published = Api 'PATCH' "$repoUrl/releases/$($release.id)" @{ draft = $false; prerelease = $true; body = $body }
    Write-Output "Release: $($published.html_url)"
    $published.assets | ForEach-Object { Write-Output "Download: $($_.browser_download_url)" }
} finally { Pop-Location; $headers.Clear(); $credential = $null; $line = $null }
