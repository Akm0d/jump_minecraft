#requires -Version 5.1
# Regression tests for install_client.ps1; run under Windows PowerShell 5.1: powershell -File scripts\tests\Test-InstallClient.ps1
Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "..\install_client.ps1"
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path $scriptPath), [ref]$tokens, [ref]$errors)
if ($errors.Count -gt 0) { throw "install_client.ps1 has parse errors: $($errors[0].Message)" }

# Load only the functions under test; the script itself requires mandatory parameters.
$names = "Get-JavaMajor", "Test-JavaTls", "Find-CompatibleJava", "Save-JsonFile", "Invoke-ExternalCommand", "Install-OfficialLauncher", "Write-Step", "Write-Ready", "Write-Notice"
foreach ($fn in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $names -contains $n.Name }, $true)) {
    . ([scriptblock]::Create($fn.Extent.Text))
}

$script:Failures = 0
function Assert-True([bool]$Condition, [string]$Message) {
    if ($Condition) { Write-Host "PASS $Message" } else { Write-Host "FAIL $Message" -ForegroundColor Red; $script:Failures++ }
}

$work = Join-Path ([IO.Path]::GetTempPath()) ("jump-install-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $work | Out-Null
$originalPath = $env:PATH
try {
    # Get-JavaMajor
    $javaStderr = Join-Path $work "java25.cmd"
    Set-Content -LiteralPath $javaStderr -Encoding ASCII -Value "@echo off`r`necho openjdk version `"25.0.1`" 2026-10-21 1>&2`r`nexit /b 0"
    Assert-True ((Get-JavaMajor $javaStderr) -eq 25) "Get-JavaMajor parses stderr output under Stop"

    $java8 = Join-Path $work "java8.cmd"
    Set-Content -LiteralPath $java8 -Encoding ASCII -Value "@echo off`r`necho java version `"1.8.0_401`" 1>&2`r`nexit /b 0"
    Assert-True ((Get-JavaMajor $java8) -eq 1) "Get-JavaMajor reports legacy major"

    $javaFail = Join-Path $work "javafail.cmd"
    Set-Content -LiteralPath $javaFail -Encoding ASCII -Value "@echo off`r`necho boom 1>&2`r`nexit /b 3"
    Assert-True ((Get-JavaMajor $javaFail) -eq 0) "Get-JavaMajor returns 0 on non-zero exit"
    Assert-True ((Get-JavaMajor (Join-Path $work "missing.exe")) -eq 0) "Get-JavaMajor returns 0 for missing binary"

    # Install-OfficialLauncher
    $wingetCmd = Join-Path $work "winget.cmd"
    Set-Content -LiteralPath $wingetCmd -Encoding ASCII -Value "@echo off`r`necho Found Minecraft Launcher`r`necho a warning 1>&2`r`nexit /b 0"
    $env:PATH = "$work;$originalPath"

    $ValidateOnly = $false; $DryRun = $false
    $script:StateCalls = 0
    function Get-LauncherState {
        $script:StateCalls++
        [pscustomobject]@{ Installed = ($script:StateCalls -gt 1); Executable = "C:\fake\MinecraftLauncher.exe"; AppId = $null }
    }
    $result = @(Install-OfficialLauncher 6>$null)
    Assert-True ($result.Count -eq 1) "Install-OfficialLauncher returns exactly one object (got $($result.Count))"
    Assert-True ($result.Count -eq 1 -and $result[0].Executable -eq "C:\fake\MinecraftLauncher.exe") "Returned object is the launcher state"

    Set-Content -LiteralPath $wingetCmd -Encoding ASCII -Value "@echo off`r`nexit /b 5"
    $script:StateCalls = 0
    $threw = $false
    try { [void](Install-OfficialLauncher 6>$null) } catch { $threw = $true }
    Assert-True $threw "Install-OfficialLauncher throws when winget fails"

    # Java 25 whose version is right but whose TLS init fails when the probe runs.
    $script:CacheRoot = Join-Path $work "cache"
    $script:AppRoot = Join-Path $work "app"
    $MinecraftHome = Join-Path $work "mc"
    $script:TlsProbeClassBase64 = [Convert]::ToBase64String([byte[]](0xCA, 0xFE, 0xBA, 0xBE))
    $badTls = Join-Path $work "java25-badtls.cmd"
    Set-Content -LiteralPath $badTls -Encoding ASCII -Value (("@echo off", "echo %* | findstr /c:TlsProbe >nul && (echo java.security.KeyStoreException: problem accessing trust store 1>&2 & exit /b 1)", "echo openjdk version `"25.0.3`" 1>&2", "exit /b 0") -join "`r`n")
    $goodTls = Join-Path $work "java25-goodtls.cmd"
    Set-Content -LiteralPath $goodTls -Encoding ASCII -Value (("@echo off", "echo %* | findstr /c:TlsProbe >nul && (echo TLS_OK & exit /b 0)", "echo openjdk version `"25.0.3`" 1>&2", "exit /b 0") -join "`r`n")
    Assert-True ((Get-JavaMajor $badTls) -eq 25) "Broken-TLS candidate reports Java 25"
    Assert-True (-not (Test-JavaTls $badTls)) "Test-JavaTls rejects Java 25 with broken TLS"
    Assert-True (Test-JavaTls $goodTls) "Test-JavaTls accepts Java 25 with working TLS"

    function Get-Command { param($Name, $ErrorAction) [pscustomobject]@{ Source = $script:FakeJava } }
    $script:FakeJava = $badTls
    Assert-True ($null -eq (Find-CompatibleJava 3>$null)) "Find-CompatibleJava skips Java 25 with broken TLS"
    $script:FakeJava = $goodTls
    Assert-True ((Find-CompatibleJava) -eq $goodTls) "Find-CompatibleJava selects Java 25 with working TLS"
    Remove-Item function:Get-Command

    # Save-JsonFile must write the backup to the timestamped path, not to a file named after the boolean.
    $jsonDir = Join-Path $work "profiles"
    New-Item -ItemType Directory -Path $jsonDir | Out-Null
    $jsonFile = Join-Path $jsonDir "launcher_profiles.json"
    $original = '{"profiles":{"keep":{"name":"existing"}}}'
    [IO.File]::WriteAllText($jsonFile, $original)
    Push-Location $work
    try { Save-JsonFile $jsonFile ([ordered]@{ profiles = [ordered]@{} }) $true 6>$null } finally { Pop-Location }
    $backups = @(Get-ChildItem -LiteralPath $jsonDir -Filter "launcher_profiles.json.jump-backup-*")
    Assert-True ($backups.Count -eq 1) "Save-JsonFile creates one timestamped backup next to the target"
    Assert-True ($backups.Count -eq 1 -and [IO.File]::ReadAllText($backups[0].FullName) -eq $original) "Backup contains the original contents"
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $work "True"))) "No file named True is created"
} finally {
    $env:PATH = $originalPath
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:Failures -gt 0) { Write-Host "$script:Failures test(s) failed" -ForegroundColor Red; exit 1 }
Write-Host "All tests passed"
