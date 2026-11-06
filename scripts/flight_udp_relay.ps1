[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$StationId,
    [string]$StationName = $env:COMPUTERNAME,
    [string]$PaperHost = "10.1.10.158",
    [ValidateRange(1, 65535)]
    [int]$PaperPort = 12101,
    [string]$FlightHost = "127.0.0.1",
    [ValidateRange(1, 65535)]
    [int]$FlightPort = 1201,
    [ValidateRange(1, 30)]
    [int]$RegistrationIntervalSeconds = 1,
    [string]$HealthPath,
    [switch]$ValidateOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Test-PrivateIPv4([Net.IPAddress]$Address) {
    $bytes = $Address.GetAddressBytes()
    if ($bytes.Length -ne 4) { return $false }
    if ($bytes[0] -eq 127 -or $bytes[0] -eq 10) { return $true }
    if ($bytes[0] -eq 192 -and $bytes[1] -eq 168) { return $true }
    if ($bytes[0] -eq 172 -and $bytes[1] -ge 16 -and $bytes[1] -le 31) { return $true }
    return $bytes[0] -eq 100 -and $bytes[1] -ge 64 -and $bytes[1] -le 127
}

function Resolve-PrivateIPv4([string]$HostName, [string]$Description) {
    $parsed = $null
    if ([Net.IPAddress]::TryParse($HostName, [ref]$parsed)) {
        $resolved = $parsed
    } else {
        $resolved = [Net.Dns]::GetHostAddresses($HostName) |
            Where-Object { $_.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork } |
            Select-Object -First 1
    }
    if (-not $resolved) { throw "Could not resolve an IPv4 address for $Description '$HostName'." }
    if (-not (Test-PrivateIPv4 $resolved)) {
        throw "$Description '$HostName' resolved to non-private address $resolved. Refusing public FLIGHT traffic."
    }
    return $resolved
}

function Test-EndpointEqual([Net.IPEndPoint]$Left, [Net.IPEndPoint]$Right) {
    return $Left.Port -eq $Right.Port -and $Left.Address.Equals($Right.Address)
}

function Read-Heartbeat([byte[]]$Data) {
    try {
        $message = ([Text.Encoding]::UTF8.GetString($Data) | ConvertFrom-Json)
        $names = @($message.PSObject.Properties | ForEach-Object { $_.Name })
        if ($names.Count -ne 3 -or
            $names -notcontains "findex" -or
            $names -notcontains "expiresInSeconds" -or
            $names -notcontains "helicopterFlag" -or
            $message.helicopterFlag -isnot [bool]) {
            return $null
        }
        $indexValue = [double]$message.findex
        $expiry = [double]$message.expiresInSeconds
        if ([double]::IsNaN($indexValue) -or [double]::IsInfinity($indexValue) -or
            $indexValue -lt 0 -or [Math]::Floor($indexValue) -ne $indexValue -or
            $indexValue -gt [long]::MaxValue -or
            [double]::IsNaN($expiry) -or [double]::IsInfinity($expiry) -or
            $expiry -le 0 -or $expiry -gt 30 -or
            [bool]$message.helicopterFlag -ne $false) {
            return $null
        }
        return [pscustomobject]@{ Findex = [long]$indexValue; ExpirySeconds = $expiry }
    } catch {
        return $null
    }
}

function Read-RegistrationAck([byte[]]$Data) {
    try {
        $message = ([Text.Encoding]::UTF8.GetString($Data) | ConvertFrom-Json)
        if ([int]$message.jumpStationAck -ne 1 -or [string]$message.stationId -ne $StationId) {
            return $null
        }
        return [string]$message.status
    } catch {
        return $null
    }
}

function Save-Health([string]$State, [string]$Detail) {
    if ([string]::IsNullOrWhiteSpace($HealthPath)) { return }
    try {
        $health = [ordered]@{
            state = $State
            detail = $Detail
            stationId = $StationId
            stationName = $StationName
            paper = "$($PaperHost):$PaperPort"
            flight = "$($FlightHost):$FlightPort"
            updatedUtc = [DateTime]::UtcNow.ToString("o")
        }
        $temporary = "$HealthPath.tmp"
        $json = $health | ConvertTo-Json -Compress
        [IO.File]::WriteAllText($temporary, $json, (New-Object Text.UTF8Encoding($false)))
        Move-Item -LiteralPath $temporary -Destination $HealthPath -Force
    } catch {
        Write-Warning "Could not update bridge health file: $($_.Exception.Message)"
    }
}

if ($ConfigPath) {
    $configuration = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    if (-not $StationId) { $StationId = [string]$configuration.stationId }
    if (-not $PSBoundParameters.ContainsKey("StationName") -and $configuration.stationName) {
        $StationName = [string]$configuration.stationName
    }
    if (-not $PSBoundParameters.ContainsKey("PaperHost")) { $PaperHost = [string]$configuration.paperHost }
    if (-not $PSBoundParameters.ContainsKey("PaperPort")) { $PaperPort = [int]$configuration.paperPort }
    if (-not $PSBoundParameters.ContainsKey("FlightHost")) { $FlightHost = [string]$configuration.flightHost }
    if (-not $PSBoundParameters.ContainsKey("FlightPort")) { $FlightPort = [int]$configuration.flightPort }
    if (-not $PSBoundParameters.ContainsKey("RegistrationIntervalSeconds") -and
        $configuration.registrationIntervalSeconds) {
        $RegistrationIntervalSeconds = [int]$configuration.registrationIntervalSeconds
    }
    if (-not $HealthPath -and $configuration.healthPath) { $HealthPath = [string]$configuration.healthPath }
}

if ([string]::IsNullOrWhiteSpace($StationId) -or $StationId -notmatch '^[A-Za-z0-9._-]{1,64}$') {
    throw "StationId is required and must contain 1-64 letters, digits, dots, underscores, or hyphens."
}
if ([string]::IsNullOrWhiteSpace($StationName) -or $StationName.Length -gt 80) {
    throw "StationName must contain 1-80 characters."
}

$paperIp = Resolve-PrivateIPv4 $PaperHost "Paper host"
$flightIp = Resolve-PrivateIPv4 $FlightHost "FLIGHT host"
if ($flightIp.GetAddressBytes()[0] -ne 127) {
    throw "FLIGHT host '$FlightHost' resolved to $flightIp. The station bridge only permits a local loopback FLIGHT engine."
}
$paperEndpoint = New-Object Net.IPEndPoint($paperIp, $PaperPort)
$flightEndpoint = New-Object Net.IPEndPoint($flightIp, $FlightPort)
$registrationJson = [ordered]@{
    jumpStation = 1
    stationId = $StationId
    stationName = $StationName
    bridgeVersion = 1
} | ConvertTo-Json -Compress
$registration = [Text.Encoding]::UTF8.GetBytes($registrationJson)

Write-Host "JUMP station bridge validation" -ForegroundColor Cyan
Write-Host "  Station:        $StationName ($StationId)"
Write-Host "  Paper registry: $PaperHost ($paperIp):$PaperPort"
Write-Host "  FLIGHT target:  $FlightHost ($flightIp):$FlightPort"
Write-Host "  Public traffic: disabled"
if ($ValidateOnly) {
    Save-Health "validated" "Configuration is valid; no sockets were opened."
    Write-Host "Configuration is valid; no sockets were opened." -ForegroundColor Green
    exit 0
}

$paperSocket = $null
$flightSocket = $null
$failed = $false
try {
    $paperSocket = New-Object Net.Sockets.UdpClient(0)
    $flightSocket = New-Object Net.Sockets.UdpClient(0)
    $paperSocket.Client.Blocking = $false
    $flightSocket.Client.Blocking = $false
    $nextRegistration = [DateTime]::MinValue
    $nextResolve = [DateTime]::UtcNow.AddSeconds(30)
    $lastHeartbeatFindex = [long]::MinValue
    $paperLeaseExpires = [DateTime]::MinValue
    $registrationStatus = "waiting"
    $registrations = 0L
    $heartbeats = 0L
    $frames = 0L
    $rejected = 0L
    $nextStatus = [DateTime]::UtcNow.AddSeconds(10)

    Save-Health "starting" "Waiting for Paper registration and FLIGHT."
    Write-Host "Station bridge running in the background. Stop with Ctrl+C." -ForegroundColor Green
    while ($true) {
        $now = [DateTime]::UtcNow
        if ($now -ge $nextResolve) {
            $paperIp = Resolve-PrivateIPv4 $PaperHost "Paper host"
            $paperEndpoint = New-Object Net.IPEndPoint($paperIp, $PaperPort)
            $nextResolve = $now.AddSeconds(30)
        }
        if ($now -ge $nextRegistration) {
            [void]$paperSocket.Send($registration, $registration.Length, $paperEndpoint)
            $registrations++
            $nextRegistration = $now.AddSeconds($RegistrationIntervalSeconds)
        }

        while ($paperSocket.Available -gt 0) {
            $source = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
            $data = $paperSocket.Receive([ref]$source)
            if (-not (Test-EndpointEqual $source $paperEndpoint)) {
                $rejected++
                continue
            }
            $ack = Read-RegistrationAck $data
            if ($ack) {
                $registrationStatus = $ack
                continue
            }
            $heartbeat = Read-Heartbeat $data
            if (-not $heartbeat -or $heartbeat.Findex -lt $lastHeartbeatFindex) {
                $rejected++
                continue
            }
            $lastHeartbeatFindex = $heartbeat.Findex
            $paperLeaseExpires = $now.AddSeconds($heartbeat.ExpirySeconds)
            [void]$flightSocket.Send($data, $data.Length, $flightEndpoint)
            $heartbeats++
        }

        while ($flightSocket.Available -gt 0) {
            $source = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
            $data = $flightSocket.Receive([ref]$source)
            if (-not (Test-EndpointEqual $source $flightEndpoint)) {
                $rejected++
                continue
            }
            if ([DateTime]::UtcNow -le $paperLeaseExpires) {
                [void]$paperSocket.Send($data, $data.Length, $paperEndpoint)
                $frames++
            }
        }

        if ($now -ge $nextStatus) {
            $state = if ($registrationStatus -in @("registered", "refreshed")) {
                if ($heartbeats -gt 0) { "healthy" } else { "registered" }
            } else {
                "waiting"
            }
            $detail = "registrations=$registrations status=$registrationStatus heartbeats=$heartbeats frames=$frames rejected=$rejected"
            Write-Host "bridge $detail"
            Save-Health $state $detail
            $nextStatus = $now.AddSeconds(10)
        }
        Start-Sleep -Milliseconds 2
    }
} catch {
    $failed = $true
    Save-Health "failed" $_.Exception.Message
    throw
} finally {
    if ($paperSocket) { $paperSocket.Dispose() }
    if ($flightSocket) { $flightSocket.Dispose() }
    if (-not $failed) { Save-Health "stopped" "Bridge process stopped." }
    Write-Host "Station bridge stopped."
}
