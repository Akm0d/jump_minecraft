[CmdletBinding()]
param(
    [string]$InstallerPath = (Join-Path $PSScriptRoot "..\scripts\install_client.ps1")
)

$ErrorActionPreference = "Stop"
$testRoot = Join-Path ([IO.Path]::GetTempPath()) (
    "jump-minecraft-installer-test-" + [guid]::NewGuid().ToString("N"))

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Assert-ThrowsLike([scriptblock]$Action, [string]$Pattern, [string]$Message) {
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "$Message (unexpected error: $($_.Exception.Message))"
    }
    throw "$Message (no error was raised)"
}

function Import-InstallerFunctions([string]$Path) {
    $source = [IO.File]::ReadAllText($Path)
    $tokens = $null
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseInput(
        $source, [ref]$tokens, [ref]$errors)
    if ($errors.Count -gt 0) { throw "Installer does not parse" }
    $functions = $ast.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $true)
    foreach ($function in $functions) {
        $definition = $function.Extent.Text
        $definition = $definition.Replace(
            "function $($function.Name)", "function global:$($function.Name)")
        $definition = $definition.Replace('$PSScriptRoot', '$global:InstallerRoot')
        $definition = $definition.Replace('$script:', '$global:')
        Invoke-Expression $definition
    }
}

$global:registered = 0
$global:started = 0
$global:stopped = 0
$global:unregistered = 0
$global:taskExists = $false
$global:taskState = "Running"
function global:New-ScheduledTaskAction { return [pscustomobject]@{ Kind = "action" } }
function global:New-ScheduledTaskTrigger { return [pscustomobject]@{ Kind = "trigger" } }
function global:New-ScheduledTaskSettingsSet { return [pscustomobject]@{ Kind = "settings" } }
function global:Register-ScheduledTask {
    $global:registered++
    $global:taskExists = $true
    return [pscustomobject]@{ State = "Ready" }
}
function global:Start-ScheduledTask { $global:started++ }
function global:Stop-ScheduledTask {
    if ($global:taskExists) { $global:stopped++ }
}
function global:Get-ScheduledTask {
    if ($global:taskExists) { return [pscustomobject]@{ State = $global:taskState } }
    return $null
}
function global:Unregister-ScheduledTask {
    $global:unregistered++
    $global:taskExists = $false
}

try {
    Import-InstallerFunctions $InstallerPath

    $global:AppRoot = Join-Path $testRoot "local\JUMP Minecraft"
    $global:InstallerRoot = Split-Path -Parent $InstallerPath
    $global:CacheRoot = Join-Path $global:AppRoot "cache"
    $global:ProfileId = "jump-minecraft-vr-26-3"
    $global:ProfileName = "JUMP Minecraft VR (26.3)"
    $global:VivecraftAsset = "vivecraft-26.3-1.3.17-fabric.jar"
    $global:BridgeTaskName = "JUMP Minecraft Station Bridge Test"
    $global:BridgeRoot = Join-Path $global:AppRoot "bridge"
    $global:BridgeScript = Join-Path $global:BridgeRoot "flight_udp_relay.ps1"
    $global:BridgeConfig = Join-Path $global:AppRoot "station-bridge.json"
    $global:BridgeHealth = Join-Path $global:AppRoot "station-bridge-health.json"
    $MinecraftHome = Join-Path $testRoot "minecraft"
    $ProfileDirectory = Join-Path $global:AppRoot "profiles\26.3-vr"
    $MinecraftVersion = "26.3"
    $FabricLoaderVersion = "0.20.1"
    $StationName = "Idempotency Test"
    $PaperHost = "127.0.0.1"
    $PaperPort = 12101
    $FlightHost = "127.0.0.1"
    $FlightPort = 1201
    $DryRun = $false
    $ValidateOnly = $false
    $Repair = $false

    New-Item -ItemType Directory -Force -Path $MinecraftHome, $ProfileDirectory | Out-Null
    $unrelated = Join-Path $MinecraftHome "unrelated-save.txt"
    [IO.File]::WriteAllText($unrelated, "preserve")
    $profileFile = Join-Path $MinecraftHome "launcher_profiles.json"
    $launcher = [ordered]@{
        profiles = [ordered]@{
            $global:ProfileId = [ordered]@{
                name = $global:ProfileName
                gameDir = $ProfileDirectory
                lastVersionId = "fabric-loader-0.20.1-26.3"
            }
            unrelated = [ordered]@{ name = "Unrelated"; gameDir = "C:\Other" }
        }
    }
    [IO.File]::WriteAllText(
        $profileFile,
        ($launcher | ConvertTo-Json -Depth 10),
        (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $ProfileDirectory "owned.txt"), "owned")

    Install-StationBridge
    $first = Get-Content $global:BridgeConfig -Raw | ConvertFrom-Json
    Install-StationBridge
    $second = Get-Content $global:BridgeConfig -Raw | ConvertFrom-Json

    Assert-True ($first.stationId -eq $second.stationId) `
        "Station identity changed on repeat install"
    Assert-True (@(Get-ChildItem "$global:BridgeConfig.jump-backup-*").Count -eq 1) `
        "Repeat install did not preserve the previous bridge configuration"
    Assert-True ($first.stationId -match '^[A-Za-z0-9._-]{1,64}$') `
        "Station identity is invalid"
    Assert-True (Test-Path $global:BridgeScript) "Managed bridge script was not installed"
    Assert-True ($global:registered -eq 2 -and $global:started -eq 2) `
        "Scheduled task was not safely replaced and restarted"
    Assert-True ($global:stopped -eq 1) `
        "Running bridge task was not stopped before upgrade"
    Assert-StationBridge

    $global:taskState = "Ready"
    Assert-ThrowsLike { Assert-StationBridge } "*scheduled task is Ready*" `
        "Validation accepted a stopped bridge task"
    $global:taskState = "Running"
    $failedHealth = [ordered]@{
        state = "failed"
        detail = "test failure"
        updatedUtc = [DateTime]::UtcNow.ToString("o")
    }
    [IO.File]::WriteAllText(
        $global:BridgeHealth,
        ($failedHealth | ConvertTo-Json),
        (New-Object Text.UTF8Encoding($false)))
    Assert-ThrowsLike { Assert-StationBridge } "*health is failed*" `
        "Validation accepted failed bridge health"
    $failedHealth.state = "waiting"
    $failedHealth.updatedUtc = [DateTime]::UtcNow.AddMinutes(-5).ToString("o")
    [IO.File]::WriteAllText(
        $global:BridgeHealth,
        ($failedHealth | ConvertTo-Json),
        (New-Object Text.UTF8Encoding($false)))
    Assert-ThrowsLike { Assert-StationBridge } "*health is stale*" `
        "Validation accepted stale bridge health"

    Uninstall-JumpMinecraft
    $after = Get-Content $profileFile -Raw | ConvertFrom-Json
    Assert-True (-not $after.profiles.PSObject.Properties[$global:ProfileId]) `
        "Dedicated launcher profile remains"
    Assert-True ($null -ne $after.profiles.PSObject.Properties["unrelated"]) `
        "Unrelated launcher profile was removed"
    Assert-True (Test-Path $unrelated) "Unrelated Minecraft data was removed"
    Assert-True (@(Get-ChildItem "$profileFile.jump-backup-*").Count -eq 1) `
        "Uninstall did not preserve the previous launcher profile JSON"
    Assert-True (-not (Test-Path $ProfileDirectory)) "Dedicated profile was not removed"
    Assert-True (-not (Test-Path $global:BridgeRoot)) "Managed bridge directory was not removed"
    Assert-True ($global:unregistered -eq 1) "Scheduled task was not unregistered"
    Assert-True ($global:stopped -eq 2) "Scheduled task was not stopped before uninstall"
    $archives = @(Get-ChildItem (Join-Path $global:AppRoot "uninstalled") -Directory)
    Assert-True ($archives.Count -eq 1) "Recoverable uninstall archive was not created"
    Assert-True (Test-Path (Join-Path $archives[0].FullName "profile\owned.txt")) `
        "Owned profile was not archived"

    Write-Output "installer-idempotency: PASS"
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
