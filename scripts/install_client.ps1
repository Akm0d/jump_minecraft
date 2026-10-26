[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MinecraftServer,
    [string]$FlightHost = "127.0.0.1",
    [ValidateRange(1, 65535)]
    [int]$FlightPort = 1201,
    [string]$MinecraftVersion = "26.3",
    [string]$FabricLoaderVersion = "0.20.1",
    [string]$FabricInstallerVersion = "1.1.2",
    [string]$VivecraftVersion = "26.3-1.3.17",
    [string]$RelayListenAddress,
    [ValidateRange(1, 65535)]
    [int]$RelayListenPort = 12100,
    [string]$RelayAllowedPaperAddress,
    [string]$MinecraftHome = (Join-Path $env:APPDATA ".minecraft"),
    [string]$ProfileDirectory = (Join-Path $env:LOCALAPPDATA "JUMP Minecraft\profiles\26.3-vr"),
    [switch]$DryRun,
    [switch]$ValidateOnly,
    [switch]$Repair,
    [switch]$NonInteractive
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$script:AppRoot = Join-Path $env:LOCALAPPDATA "JUMP Minecraft"
$script:CacheRoot = Join-Path $script:AppRoot "cache"
$script:ProfileId = "jump-minecraft-vr-$($MinecraftVersion.Replace('.', '-'))"
$script:ProfileName = "JUMP Minecraft VR ($MinecraftVersion)"
$script:VivecraftAsset = "vivecraft-$VivecraftVersion-fabric.jar"
$script:UserAgent = "jump-minecraft-client-installer/0.1"

function Write-Step([string]$Message) {
    Write-Host "[JUMP] $Message" -ForegroundColor Cyan
}

function Write-Ready([string]$Message) {
    Write-Host "[ OK ] $Message" -ForegroundColor Green
}

function Write-Notice([string]$Message) {
    Write-Warning $Message
}

function Assert-WindowsHost {
    if ($env:OS -ne "Windows_NT") {
        throw "This installer must run in Windows PowerShell or PowerShell on Windows."
    }
    $windowsVersion = [Environment]::OSVersion.Version
    if ($windowsVersion.Major -lt 10) {
        throw "Unsupported Windows version '$windowsVersion'. Windows 10 or newer is required."
    }
    $architecture = if ($env:PROCESSOR_ARCHITEW6432) {
        $env:PROCESSOR_ARCHITEW6432
    } else {
        $env:PROCESSOR_ARCHITECTURE
    }
    if ($architecture -ne "AMD64") {
        throw "Unsupported architecture '$architecture'. This workflow is validated only for 64-bit x86 Windows."
    }
    Write-Ready "Windows $windowsVersion on $architecture"
}

function Assert-ServerAddress([string]$Address) {
    if ([string]::IsNullOrWhiteSpace($Address) -or $Address -match "\s|://|/" ) {
        throw "MinecraftServer must be a hostname or host:port, not a URL."
    }
}

function Get-UpstreamJson([string]$Uri) {
    Write-Step "Checking $Uri"
    return (Invoke-RestMethod -Uri $Uri -Headers @{ "User-Agent" = $script:UserAgent } -UseBasicParsing)
}

function Invoke-TrustedDownload(
    [string]$Uri,
    [string]$Destination,
    [string]$ExpectedSha256
) {
    if (Test-Path -LiteralPath $Destination) {
        $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -eq $ExpectedSha256.ToLowerInvariant() -and -not $Repair) {
            Write-Ready "Verified cached $([IO.Path]::GetFileName($Destination))"
            return
        }
        if ($actual -eq $ExpectedSha256.ToLowerInvariant()) {
            Write-Step "Repair requested; refreshing $([IO.Path]::GetFileName($Destination))"
        } else {
            Write-Notice "Cached file hash is wrong; it will be replaced: $Destination"
        }
    }
    if ($DryRun -or $ValidateOnly) {
        throw "Missing or invalid required file in validation-only mode: $Destination"
    }
    $parent = Split-Path -Parent $Destination
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    $temporary = "$Destination.download"
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    Write-Step "Downloading $Uri"
    Invoke-WebRequest -Uri $Uri -OutFile $temporary -Headers @{ "User-Agent" = $script:UserAgent } -UseBasicParsing
    $actual = (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedSha256.ToLowerInvariant()) {
        Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        throw "SHA-256 mismatch for $Uri. Expected $ExpectedSha256, got $actual."
    }
    Move-Item -LiteralPath $temporary -Destination $Destination -Force
    Write-Ready "Downloaded and verified $([IO.Path]::GetFileName($Destination))"
}

function Get-JavaMajor([string]$JavaPath) {
    # java -version writes to stderr, which Windows PowerShell 5.1 turns into terminating errors under Stop.
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $JavaPath
    $info.Arguments = "-version"
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    $process = $null
    try {
        $process = [System.Diagnostics.Process]::Start($info)
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            try { $process.Kill() } catch { }
            return 0
        }
        $output = $stdout.Result + "`n" + $stderr.Result
        if ($process.ExitCode -ne 0 -or $output -notmatch 'version\s+"(?<major>\d+)') {
            return 0
        }
        return [int]$Matches.major
    } catch {
        return 0
    } finally {
        if ($process) { $process.Dispose() }
    }
}

function Invoke-ExternalCommand([string]$FilePath, [string[]]$Arguments) {
    # Streams output to the host and returns only the exit code, so callers' return values stay clean.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & $FilePath @Arguments 2>&1 | ForEach-Object { Write-Host "$_" }
        return [int]$LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

# Compiled from a class that inits default TrustManagerFactory/KeyManagerFactory/SSLContext (Java 8 bytecode).
$script:TlsProbeClassBase64 = "yv66vgAAADQATwoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWCgAIAAkHAAoMAAsADAEAIWphdmF4L25ldC9zc2wvVHJ1c3RNYW5hZ2VyRmFjdG9yeQEAE2dldERlZmF1bHRBbGdvcml0aG0BABQoKUxqYXZhL2xhbmcvU3RyaW5nOwoACAAODAAPABABAAtnZXRJbnN0YW5jZQEANyhMamF2YS9sYW5nL1N0cmluZzspTGphdmF4L25ldC9zc2wvVHJ1c3RNYW5hZ2VyRmFjdG9yeTsHABIBABZqYXZhL3NlY3VyaXR5L0tleVN0b3JlCgAIABQMABUAFgEABGluaXQBABsoTGphdmEvc2VjdXJpdHkvS2V5U3RvcmU7KVYKABgACQcAGQEAH2phdmF4L25ldC9zc2wvS2V5TWFuYWdlckZhY3RvcnkKABgAGwwADwAcAQA1KExqYXZhL2xhbmcvU3RyaW5nOylMamF2YXgvbmV0L3NzbC9LZXlNYW5hZ2VyRmFjdG9yeTsKABgAHgwAFQAfAQAdKExqYXZhL3NlY3VyaXR5L0tleVN0b3JlO1tDKVYIACEBAANUTFMKACMAJAcAJQwADwAmAQAYamF2YXgvbmV0L3NzbC9TU0xDb250ZXh0AQAuKExqYXZhL2xhbmcvU3RyaW5nOylMamF2YXgvbmV0L3NzbC9TU0xDb250ZXh0OwoAGAAoDAApACoBAA5nZXRLZXlNYW5hZ2VycwEAHSgpW0xqYXZheC9uZXQvc3NsL0tleU1hbmFnZXI7CgAIACwMAC0ALgEAEGdldFRydXN0TWFuYWdlcnMBAB8oKVtMamF2YXgvbmV0L3NzbC9UcnVzdE1hbmFnZXI7CgAjADAMABUAMQEAVyhbTGphdmF4L25ldC9zc2wvS2V5TWFuYWdlcjtbTGphdmF4L25ldC9zc2wvVHJ1c3RNYW5hZ2VyO0xqYXZhL3NlY3VyaXR5L1NlY3VyZVJhbmRvbTspVgoAIwAzDAA0ADUBAApnZXREZWZhdWx0AQAcKClMamF2YXgvbmV0L3NzbC9TU0xDb250ZXh0OwkANwA4BwA5DAA6ADsBABBqYXZhL2xhbmcvU3lzdGVtAQADb3V0AQAVTGphdmEvaW8vUHJpbnRTdHJlYW07CAA9AQAGVExTX09LCgA/AEAHAEEMAEIAQwEAE2phdmEvaW8vUHJpbnRTdHJlYW0BAAdwcmludGxuAQAVKExqYXZhL2xhbmcvU3RyaW5nOylWBwBFAQAIVGxzUHJvYmUBAARDb2RlAQAPTGluZU51bWJlclRhYmxlAQAEbWFpbgEAFihbTGphdmEvbGFuZy9TdHJpbmc7KVYBAApFeGNlcHRpb25zBwBMAQATamF2YS9sYW5nL0V4Y2VwdGlvbgEAClNvdXJjZUZpbGUBAA1UbHNQcm9iZS5qYXZhACEARAACAAAAAAACAAEABQAGAAEARgAAAB0AAQABAAAABSq3AAGxAAAAAQBHAAAABgABAAAAAwAJAEgASQACAEYAAAB0AAQABAAAADy4AAe4AA1MKwHAABG2ABO4ABe4ABpNLAEBtgAdEiC4ACJOLSy2ACcrtgArAbYAL7gAMleyADYSPLYAPrEAAAABAEcAAAAmAAkAAAAFAAcABgAPAAcAFgAIABwACQAiAAoALwALADMADAA7AA0ASgAAAAQAAQBLAAEATQAAAAIATg=="

function Test-JavaTls([string]$JavaPath) {
    # Fabric's installer selects the Windows-ROOT trust store; runtimes without SunMSCAPI fail here.
    $probeDir = Join-Path $script:CacheRoot "tls-probe"
    try {
        New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
        [IO.File]::WriteAllBytes((Join-Path $probeDir "TlsProbe.class"), [Convert]::FromBase64String($script:TlsProbeClassBase64))
    } catch {
        return $false
    }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $JavaPath
    $info.Arguments = "-Djavax.net.ssl.trustStoreType=Windows-ROOT -cp `"$probeDir`" TlsProbe"
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    $process = $null
    try {
        $process = [System.Diagnostics.Process]::Start($info)
        $stdout = $process.StandardOutput.ReadToEndAsync()
        [void]$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) {
            try { $process.Kill() } catch { }
            return $false
        }
        return ($process.ExitCode -eq 0 -and $stdout.Result -match 'TLS_OK')
    } catch {
        return $false
    } finally {
        if ($process) { $process.Dispose() }
    }
}

function Find-CompatibleJava {
    $candidates = New-Object System.Collections.Generic.List[string]
    # The private Temurin runtime is preferred over anything installed system-wide.
    foreach ($root in @((Join-Path $script:AppRoot "runtime"))) {
        if (Test-Path -LiteralPath $root) {
            Get-ChildItem -LiteralPath $root -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -notmatch '\.(staging|backup-\d+)\\' } |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    $command = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { $candidates.Add($command.Source) }
    $minecraftRuntime = Join-Path $MinecraftHome "runtime"
    if (Test-Path -LiteralPath $minecraftRuntime) {
        Get-ChildItem -LiteralPath $minecraftRuntime -Filter java.exe -File -Recurse -ErrorAction SilentlyContinue |
            ForEach-Object { $candidates.Add($_.FullName) }
    }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ((Get-JavaMajor $candidate) -lt 25) { continue }
        if (Test-JavaTls $candidate) { return $candidate }
        Write-Notice "Skipping Java at $candidate because TLS initialization failed."
    }
    return $null
}

function Install-PortableJava {
    if ($ValidateOnly) {
        throw "No Java 25 runtime was found. Run without -ValidateOnly to install a private Temurin runtime."
    }
    $assets = @(Get-UpstreamJson "https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse")
    if ($assets.Count -lt 1) { throw "Adoptium returned no Windows x64 Java 25 runtime." }
    $package = $assets[0].binary.package
    $archive = Join-Path $script:CacheRoot $package.name
    if ($DryRun) {
        Write-Step "DRY RUN: would install private Java 25 from $($package.link)"
        return $null
    }
    Invoke-TrustedDownload $package.link $archive $package.checksum
    $runtimeRoot = Join-Path $script:AppRoot "runtime\temurin-25"
    $staging = "$runtimeRoot.staging"
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $staging | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $staging -Force
    $java = Get-ChildItem -LiteralPath $staging -Filter java.exe -File -Recurse |
        Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
        Select-Object -First 1
    if (-not $java -or (Get-JavaMajor $java.FullName) -lt 25 -or -not (Test-JavaTls $java.FullName)) {
        throw "The downloaded Temurin archive did not contain a usable Java 25 runtime with working TLS."
    }
    $relative = $java.FullName.Substring($staging.Length).TrimStart('\')
    if (Test-Path -LiteralPath $runtimeRoot) {
        $runtimeBackup = "$runtimeRoot.backup-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))"
        Move-Item -LiteralPath $runtimeRoot -Destination $runtimeBackup
    }
    Move-Item -LiteralPath $staging -Destination $runtimeRoot
    return (Join-Path $runtimeRoot $relative)
}

function Get-LauncherState {
    $win32Paths = @()
    foreach ($base in @(${env:ProgramFiles(x86)}, $env:ProgramFiles)) {
        if ($base) {
            $candidate = Join-Path $base "Minecraft Launcher\MinecraftLauncher.exe"
            if (Test-Path -LiteralPath $candidate) { $win32Paths += $candidate }
        }
    }
    $app = $null
    if (Get-Command Get-AppxPackage -ErrorAction SilentlyContinue) {
        $app = Get-AppxPackage -Name "*Minecraft*" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match "MinecraftLauncher|4297127D64EC6" } |
            Select-Object -First 1
    }
    $startApp = $null
    if (Get-Command Get-StartApps -ErrorAction SilentlyContinue) {
        $startApp = Get-StartApps -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq "Minecraft Launcher" } |
            Select-Object -First 1
    }
    return [pscustomobject]@{
        Installed = (($win32Paths.Count -gt 0) -or ($null -ne $app) -or ($null -ne $startApp))
        Executable = if ($win32Paths.Count -gt 0) { $win32Paths[0] } else { $null }
        AppId = if ($startApp) { $startApp.AppID } else { $null }
    }
}

function Install-OfficialLauncher {
    $state = Get-LauncherState
    if ($state.Installed) {
        Write-Ready "Official Minecraft Launcher detected"
        return $state
    }
    if ($ValidateOnly) { throw "The official Minecraft Launcher is not installed." }
    if ($DryRun) {
        Write-Step "DRY RUN: would install Mojang.MinecraftLauncher using winget"
        return $state
    }
    $winget = Get-Command winget -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $winget) {
        throw "winget is unavailable. Install the official launcher from https://www.minecraft.net/download and rerun."
    }
    Write-Step "Installing the official Minecraft Launcher with winget"
    $exitCode = Invoke-ExternalCommand $winget.Source @(
        "install", "--id", "Mojang.MinecraftLauncher", "--exact", "--source", "winget",
        "--accept-package-agreements", "--accept-source-agreements")
    if ($exitCode -ne 0) { throw "winget could not install the official Minecraft Launcher." }
    $state = Get-LauncherState
    if (-not $state.Installed) { throw "Launcher installation completed but the launcher was not detected." }
    return $state
}

function Start-OfficialLauncher($LauncherState) {
    if ($LauncherState.Executable) {
        Start-Process -FilePath $LauncherState.Executable
    } elseif ($LauncherState.AppId) {
        Start-Process -FilePath explorer.exe -ArgumentList "shell:AppsFolder\$($LauncherState.AppId)"
    } else {
        Start-Process "https://www.minecraft.net/download"
    }
}

function Get-LauncherProfileFile {
    $candidates = @(@(
        (Join-Path $MinecraftHome "launcher_profiles_microsoft_store.json"),
        (Join-Path $MinecraftHome "launcher_profiles.json")
    ) | Where-Object { Test-Path -LiteralPath $_ })
    if ($candidates.Count -eq 0) { return $null }
    return ($candidates | Sort-Object { (Get-Item -LiteralPath $_).LastWriteTimeUtc } -Descending | Select-Object -First 1)
}

function Wait-ForLauncherInitialization($LauncherState) {
    $profileFile = Get-LauncherProfileFile
    if ($profileFile) { return $profileFile }
    if ($ValidateOnly) { throw "The launcher has not created a launcher profile file yet." }
    if ($DryRun) {
        Write-Step "DRY RUN: would pause for launcher sign-in and initial profile creation"
        return (Join-Path $MinecraftHome "launcher_profiles_microsoft_store.json")
    }
    if ($NonInteractive) {
        throw "Open the official launcher, sign in, close it, then rerun without -NonInteractive."
    }
    Write-Notice "The launcher must initialize its profile store. Sign in with the licensed Microsoft account, then close the launcher."
    Start-OfficialLauncher $LauncherState
    [void](Read-Host "Press Enter after the launcher is fully closed")
    $profileFile = Get-LauncherProfileFile
    if (-not $profileFile) { throw "No launcher profile file appeared after initialization." }
    return $profileFile
}

function Assert-LauncherClosed {
    $running = Get-Process -Name MinecraftLauncher -ErrorAction SilentlyContinue
    if ($running) { throw "Close the Minecraft Launcher before installing Fabric or updating its profile." }
}

function Confirm-UpstreamVersions {
    $manifest = Get-UpstreamJson "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    if (-not ($manifest.versions | Where-Object { $_.id -eq $MinecraftVersion -and $_.type -eq "release" })) {
        throw "Minecraft $MinecraftVersion is not present as a release in Mojang's manifest."
    }
    $loaders = @(Get-UpstreamJson "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion")
    $loader = $loaders | Where-Object { $_.loader.version -eq $FabricLoaderVersion } | Select-Object -First 1
    if (-not $loader) { throw "Fabric Loader $FabricLoaderVersion is not published for Minecraft $MinecraftVersion." }
    $installers = @(Get-UpstreamJson "https://meta.fabricmc.net/v2/versions/installer")
    $installer = $installers | Where-Object { $_.version -eq $FabricInstallerVersion } | Select-Object -First 1
    if (-not $installer) { throw "Fabric Installer $FabricInstallerVersion is not published." }
    $release = Get-UpstreamJson "https://api.github.com/repos/Vivecraft/VivecraftMod/releases/tags/$VivecraftVersion"
    $asset = $release.assets | Where-Object { $_.name -eq $script:VivecraftAsset } | Select-Object -First 1
    if (-not $asset -or -not $asset.digest -or $asset.digest -notmatch '^sha256:(?<hash>[0-9a-fA-F]{64})$') {
        throw "The official Vivecraft release has no verifiable Fabric asset named $script:VivecraftAsset."
    }
    $vivecraftHash = $asset.digest.Substring(7).ToLowerInvariant()
    if ([version]$FabricLoaderVersion -lt [version]"0.16.1") {
        throw "Vivecraft $VivecraftVersion requires Fabric Loader 0.16.1 or newer."
    }
    return [pscustomobject]@{
        FabricInstallerUrl = $installer.url
        VivecraftUrl = $asset.browser_download_url
        VivecraftHash = $vivecraftHash
    }
}

function Install-Fabric([string]$JavaPath, $Versions) {
    $installer = Join-Path $script:CacheRoot "fabric-installer-$FabricInstallerVersion.jar"
    $hashUri = "$($Versions.FabricInstallerUrl).sha256"
    $hashResponse = Invoke-WebRequest -Uri $hashUri -Headers @{ "User-Agent" = $script:UserAgent } -UseBasicParsing
    $hashContent = if ($hashResponse.Content -is [byte[]]) {
        [Text.Encoding]::ASCII.GetString($hashResponse.Content)
    } else {
        [string]$hashResponse.Content
    }
    $expectedHash = $hashContent.Trim()
    if ($DryRun) {
        Write-Step "DRY RUN: would install Fabric Loader $FabricLoaderVersion into $MinecraftHome"
        return
    }
    Invoke-TrustedDownload $Versions.FabricInstallerUrl $installer $expectedHash
    Write-Step "Installing Fabric Loader $FabricLoaderVersion without altering another launcher profile"
    & $JavaPath -jar $installer client -dir $MinecraftHome -mcversion $MinecraftVersion `
        -loader $FabricLoaderVersion -noprofile
    if ($LASTEXITCODE -ne 0) { throw "Fabric Installer exited with code $LASTEXITCODE." }
    $versionId = "fabric-loader-$FabricLoaderVersion-$MinecraftVersion"
    $versionJson = Join-Path $MinecraftHome "versions\$versionId\$versionId.json"
    if (-not (Test-Path -LiteralPath $versionJson)) { throw "Fabric version metadata was not installed: $versionJson" }
    Write-Ready "Fabric version $versionId is installed"
}

function Install-Vivecraft($Versions) {
    $cachePath = Join-Path $script:CacheRoot $script:VivecraftAsset
    if ($DryRun) {
        Write-Step "DRY RUN: would install $script:VivecraftAsset into the isolated profile"
        return
    }
    Invoke-TrustedDownload $Versions.VivecraftUrl $cachePath $Versions.VivecraftHash
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($cachePath)
    try {
        $entry = $archive.GetEntry("fabric.mod.json")
        if (-not $entry) { throw "Vivecraft asset has no fabric.mod.json." }
        $reader = New-Object IO.StreamReader($entry.Open())
        try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    } finally {
        $archive.Dispose()
    }
    if ($metadata.id -ne "vivecraft" -or $metadata.version -ne $VivecraftVersion) {
        throw "Vivecraft metadata does not match the requested release."
    }
    if (-not ($metadata.depends.minecraft -contains "$MinecraftVersion.x") -or
        [version]$FabricLoaderVersion -lt [version]"0.16.1" -or
        $metadata.depends.java -ne ">=25") {
        throw "Vivecraft metadata is incompatible with the selected Minecraft, Fabric, or Java versions."
    }
    New-Item -ItemType Directory -Force -Path (Join-Path $ProfileDirectory "mods") | Out-Null
    $destination = Join-Path $ProfileDirectory "mods\$script:VivecraftAsset"
    $managedFile = Join-Path $ProfileDirectory ".jump-managed.json"
    if (Test-Path -LiteralPath $managedFile) {
        $managed = Get-Content -LiteralPath $managedFile -Raw | ConvertFrom-Json
        if ($managed.vivecraftPath -and $managed.vivecraftPath -ne $destination -and
            (Test-Path -LiteralPath $managed.vivecraftPath)) {
            $backupRoot = Join-Path $ProfileDirectory "backup"
            New-Item -ItemType Directory -Force -Path $backupRoot | Out-Null
            Move-Item -LiteralPath $managed.vivecraftPath -Destination $backupRoot -Force
        }
    }
    Copy-Item -LiteralPath $cachePath -Destination $destination -Force
    Save-JsonFile $managedFile ([ordered]@{
        vivecraftPath = $destination
        sha256 = $Versions.VivecraftHash
        updatedUtc = [DateTime]::UtcNow.ToString("o")
    }) $false
    Write-Ready "Installed Vivecraft into isolated mods directory"
}

function Save-JsonFile([string]$Path, $Value, [bool]$CreateBackup) {
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
    if ($CreateBackup -and (Test-Path -LiteralPath $Path)) {
        $backupPath = "$Path.jump-backup-$([DateTime]::UtcNow.ToString('yyyyMMddHHmmss'))"
        Copy-Item -LiteralPath $Path -Destination $backupPath
        Write-Step "Backed up $Path to $backupPath"
    }
    $temporary = "$Path.tmp"
    $json = $Value | ConvertTo-Json -Depth 100
    [IO.File]::WriteAllText($temporary, $json, (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Install-IsolatedLauncherProfile([string]$ProfileFile) {
    if ($DryRun) {
        Write-Step "DRY RUN: would create launcher profile '$script:ProfileName' using $ProfileDirectory"
        return
    }
    New-Item -ItemType Directory -Force -Path $ProfileDirectory | Out-Null
    $launcher = Get-Content -LiteralPath $ProfileFile -Raw | ConvertFrom-Json
    if (-not $launcher.profiles) {
        $launcher | Add-Member -MemberType NoteProperty -Name profiles -Value ([pscustomobject]@{})
    }
    $existing = $launcher.profiles.PSObject.Properties[$script:ProfileId]
    if ($existing -and $existing.Value.name -ne $script:ProfileName) {
        throw "Launcher profile ID '$script:ProfileId' already belongs to '$($existing.Value.name)'. Refusing to overwrite it."
    }
    $now = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ss.fffZ")
    $profile = [ordered]@{
        name = $script:ProfileName
        type = "custom"
        created = if ($existing -and $existing.Value.created) { $existing.Value.created } else { $now }
        lastUsed = $now
        icon = "Grass"
        lastVersionId = "fabric-loader-$FabricLoaderVersion-$MinecraftVersion"
        gameDir = $ProfileDirectory
    }
    if ($existing) {
        $existing.Value = [pscustomobject]$profile
    } else {
        $launcher.profiles | Add-Member -MemberType NoteProperty -Name $script:ProfileId -Value ([pscustomobject]$profile)
    }
    Save-JsonFile $ProfileFile $launcher $true
    Write-Ready "Created isolated launcher profile '$script:ProfileName'"
}

function Get-SteamVrState {
    $steamPath = $null
    foreach ($registryPath in @("HKCU:\Software\Valve\Steam", "HKLM:\Software\WOW6432Node\Valve\Steam")) {
        try {
            $candidate = (Get-ItemProperty -LiteralPath $registryPath -ErrorAction Stop).SteamPath
            if (-not $candidate) { $candidate = (Get-ItemProperty -LiteralPath $registryPath -ErrorAction Stop).InstallPath }
            if ($candidate) { $steamPath = $candidate; break }
        } catch { }
    }
    $manifest = if ($steamPath) { Join-Path $steamPath "steamapps\appmanifest_250820.acf" } else { $null }
    $installed = (($manifest -and (Test-Path -LiteralPath $manifest)) -or
        (Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA "openvr\openvrpaths.vrpath")))
    $running = $null -ne (Get-Process -Name vrserver -ErrorAction SilentlyContinue)
    return [pscustomobject]@{ Installed = $installed; Running = $running; SteamPath = $steamPath }
}

function Save-ClientConfiguration {
    if ($DryRun) { return }
    $configuration = [ordered]@{
        minecraftServer = $MinecraftServer
        minecraftVersion = $MinecraftVersion
        fabricLoaderVersion = $FabricLoaderVersion
        vivecraftVersion = $VivecraftVersion
        profileName = $script:ProfileName
        profileDirectory = $ProfileDirectory
        flight = [ordered]@{ host = $FlightHost; port = $FlightPort }
        updatedUtc = [DateTime]::UtcNow.ToString("o")
    }
    if ($RelayListenAddress) {
        $configuration["relay"] = [ordered]@{
            listenAddress = $RelayListenAddress
            listenPort = $RelayListenPort
            allowedPaperAddress = $RelayAllowedPaperAddress
        }
    }
    Save-JsonFile (Join-Path $ProfileDirectory "jump-client.json") $configuration $false
    $instructions = @"
JUMP Minecraft VR
=================
Launcher profile: $script:ProfileName
Minecraft server: $MinecraftServer

Open the official Minecraft Launcher, select the profile above, and click Play.
In Multiplayer, add the server address exactly as shown above. This installer
does not edit servers.dat or authentication data.

FLIGHT endpoint is separate from Minecraft matchmaking:
  $FlightHost`:$FlightPort
"@
    [IO.File]::WriteAllText((Join-Path $ProfileDirectory "README.txt"), $instructions, (New-Object Text.UTF8Encoding($false)))
}

function Save-RelayConfiguration {
    if (-not $RelayListenAddress) { return }
    if ([string]::IsNullOrWhiteSpace($RelayAllowedPaperAddress)) {
        throw "RelayAllowedPaperAddress is required whenever RelayListenAddress is set."
    }
    if ($DryRun) {
        Write-Step "DRY RUN: would configure the optional private FLIGHT relay on $RelayListenAddress`:$RelayListenPort"
        return
    }
    $relay = [ordered]@{
        listenAddress = $RelayListenAddress
        listenPort = $RelayListenPort
        allowedPaperAddress = $RelayAllowedPaperAddress
        flightHost = $FlightHost
        flightPort = $FlightPort
        subscriberTimeoutSeconds = 5
    }
    Save-JsonFile (Join-Path $ProfileDirectory "flight-relay.json") $relay $false
    Write-Ready "Saved private relay configuration; the relay was not started automatically"
}

function Assert-InstalledState([string]$ProfileFile, $Versions) {
    $issues = New-Object System.Collections.Generic.List[string]
    $versionId = "fabric-loader-$FabricLoaderVersion-$MinecraftVersion"
    if (-not (Test-Path -LiteralPath (Join-Path $MinecraftHome "versions\$versionId\$versionId.json"))) {
        $issues.Add("Fabric version $versionId is missing")
    }
    if (-not (Test-Path -LiteralPath (Join-Path $ProfileDirectory "mods\$script:VivecraftAsset"))) {
        $issues.Add("Vivecraft asset is missing from the isolated profile")
    } else {
        $actual = (Get-FileHash -LiteralPath (Join-Path $ProfileDirectory "mods\$script:VivecraftAsset") -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $Versions.VivecraftHash) { $issues.Add("Vivecraft asset hash is wrong") }
    }
    if (-not $ProfileFile -or -not (Test-Path -LiteralPath $ProfileFile)) {
        $issues.Add("Launcher profile store is missing")
    } else {
        $launcher = Get-Content -LiteralPath $ProfileFile -Raw | ConvertFrom-Json
        $profile = $launcher.profiles.PSObject.Properties[$script:ProfileId]
        if (-not $profile -or $profile.Value.gameDir -ne $ProfileDirectory -or
            $profile.Value.lastVersionId -ne $versionId) {
            $issues.Add("The isolated JUMP launcher profile is missing or inconsistent")
        }
    }
    if ($issues.Count -gt 0) { throw "Validation failed: $($issues -join '; ')" }
    Write-Ready "Client installation is internally consistent"
}

try {
    Assert-WindowsHost
    Assert-ServerAddress $MinecraftServer
    $hasRelayAddress = -not [string]::IsNullOrWhiteSpace($RelayListenAddress)
    $hasAllowedPaper = -not [string]::IsNullOrWhiteSpace($RelayAllowedPaperAddress)
    if ($hasRelayAddress -ne $hasAllowedPaper) {
        throw "RelayListenAddress and RelayAllowedPaperAddress must be provided together."
    }
    Write-Step "Minecraft $MinecraftVersion / Fabric $FabricLoaderVersion / Vivecraft $VivecraftVersion"
    $versions = Confirm-UpstreamVersions
    $launcher = Install-OfficialLauncher
    $profileFile = Wait-ForLauncherInitialization $launcher
    if (-not $DryRun) { Assert-LauncherClosed }

    $java = Find-CompatibleJava
    if (-not $java) { $java = Install-PortableJava }
    if ($java) { Write-Ready "Java $(Get-JavaMajor $java) at $java" }

    if ($ValidateOnly) {
        Assert-InstalledState $profileFile $versions
    } else {
        if (-not $DryRun -and -not $java) { throw "Java 25 is required to run the Fabric Installer." }
        Install-Fabric $java $versions
        Install-IsolatedLauncherProfile $profileFile
        Install-Vivecraft $versions
        Save-ClientConfiguration
        Save-RelayConfiguration
    }

    $steamVr = Get-SteamVrState
    if ($steamVr.Installed) {
        Write-Ready "SteamVR/OpenVR installation detected (running=$($steamVr.Running))"
    } else {
        Write-Notice "SteamVR was not detected. Install app 250820 from Steam, configure the headset, and verify SteamVR Home before Minecraft."
    }

    Write-Host ""
    Write-Host "Minecraft server: $MinecraftServer" -ForegroundColor Yellow
    Write-Host "Launcher profile:  $script:ProfileName" -ForegroundColor Yellow
    Write-Host "Game directory:    $ProfileDirectory" -ForegroundColor Yellow
    Write-Host "FLIGHT endpoint:   $FlightHost`:$FlightPort (not the Minecraft Playit address)" -ForegroundColor Yellow
    if ($RelayListenAddress) {
        Write-Host "Paper relay target: $RelayListenAddress`:$RelayListenPort" -ForegroundColor Yellow
        Write-Host "Start separately: .\scripts\flight_udp_relay.ps1 -ConfigPath `"$ProfileDirectory\flight-relay.json`"" -ForegroundColor Yellow
    }
    Write-Host ""
    if (-not $DryRun -and -not $ValidateOnly -and -not $NonInteractive) {
        Write-Step "Opening the official Minecraft Launcher"
        Start-OfficialLauncher $launcher
    }
    Write-Host "Open the official launcher, select '$script:ProfileName', and click Play." -ForegroundColor Green
    Write-Host "Add '$MinecraftServer' in Multiplayer. Authentication remains entirely in the official launcher." -ForegroundColor Green
} catch {
    Write-Error $_
    exit 1
}
