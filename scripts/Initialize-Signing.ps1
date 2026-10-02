param([string]$KeyDirectory = (Join-Path $env:USERPROFILE '.android\earbud-companion-signing'))
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$config = Join-Path $project 'signing.properties'
if (Test-Path -LiteralPath $config) { Write-Output 'Signing configuration already exists; preserving existing key.'; exit 0 }
$keyDirectoryFull = [IO.Path]::GetFullPath($KeyDirectory)
New-Item -ItemType Directory -Force -Path $keyDirectoryFull | Out-Null
$keyPath = Join-Path $keyDirectoryFull 'release.jks'
if (Test-Path -LiteralPath $keyPath) { throw 'Existing signing key found without configuration. Recover the original password instead of replacing the key.' }
$random = New-Object byte[] 32
$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
$generator.GetBytes($random); $generator.Dispose()
$password = [Convert]::ToBase64String($random)
$env:EARBUD_SIGNING_PASSWORD = $password
try {
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $keyPath -alias earbud-release -keyalg RSA -keysize 3072 -validity 10000 -storetype JKS -storepass:env EARBUD_SIGNING_PASSWORD -keypass:env EARBUD_SIGNING_PASSWORD -dname 'CN=Earbud Companion Personal Release'
    if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed.' }
    $text = "storeFile=$($keyPath.Replace('\','/'))`nstorePassword=$password`nkeyAlias=earbud-release`nkeyPassword=$password`n"
    [IO.File]::WriteAllText($config, $text, [Text.UTF8Encoding]::new($false))
    foreach ($path in @($keyDirectoryFull, $keyPath, $config)) {
        $acl = Get-Acl -LiteralPath $path
        $acl.SetAccessRuleProtection($true, $false)
        $rule = [Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.WindowsIdentity]::GetCurrent().Name, 'FullControl', 'Allow')
        $acl.SetAccessRule($rule)
        Set-Acl -LiteralPath $path -AclObject $acl
    }
    Write-Output 'Personal release key created and protected. Back up the key directory and signing.properties privately.'
} finally { Remove-Item Env:EARBUD_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
