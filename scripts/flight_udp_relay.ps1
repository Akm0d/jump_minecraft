[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$ListenAddress,
    [ValidateRange(1, 65535)]
    [int]$ListenPort = 12100,
    [string]$AllowedPaperAddress,
    [string]$FlightHost = "127.0.0.1",
    [ValidateRange(1, 65535)]
    [int]$FlightPort = 1201,
    [ValidateRange(1, 30)]
    [int]$SubscriberTimeoutSeconds = 5,
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
    if ($bytes[0] -eq 100 -and $bytes[1] -ge 64 -and $bytes[1] -le 127) { return $true }
    return $false
}

function Resolve-IPv4([string]$HostName) {
    $parsed = $null
    if ([Net.IPAddress]::TryParse($HostName, [ref]$parsed)) {
        if ($parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) {
            throw "IPv6 is not supported by the JUMP GameModule protocol."
        }
        return $parsed
    }
    $resolved = [Net.Dns]::GetHostAddresses($HostName) |
        Where-Object { $_.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork } |
        Select-Object -First 1
    if (-not $resolved) { throw "Could not resolve an IPv4 address for '$HostName'." }
    return $resolved
}

function Test-EndpointEqual([Net.IPEndPoint]$Left, [Net.IPEndPoint]$Right) {
    return $Left.Port -eq $Right.Port -and $Left.Address.Equals($Right.Address)
}

function Read-Heartbeat([byte[]]$Data) {
    try {
        $text = [Text.Encoding]::UTF8.GetString($Data)
        $message = $text | ConvertFrom-Json
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

if ($ConfigPath) {
    $configuration = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    if (-not $ListenAddress) { $ListenAddress = $configuration.listenAddress }
    if (-not $PSBoundParameters.ContainsKey("ListenPort")) { $ListenPort = [int]$configuration.listenPort }
    if (-not $AllowedPaperAddress) { $AllowedPaperAddress = $configuration.allowedPaperAddress }
    if (-not $PSBoundParameters.ContainsKey("FlightHost")) { $FlightHost = $configuration.flightHost }
    if (-not $PSBoundParameters.ContainsKey("FlightPort")) { $FlightPort = [int]$configuration.flightPort }
    if (-not $PSBoundParameters.ContainsKey("SubscriberTimeoutSeconds") -and $configuration.subscriberTimeoutSeconds) {
        $SubscriberTimeoutSeconds = [int]$configuration.subscriberTimeoutSeconds
    }
}

if ([string]::IsNullOrWhiteSpace($ListenAddress)) {
    throw "ListenAddress is required and must be a specific private IPv4 address."
}
if ([string]::IsNullOrWhiteSpace($AllowedPaperAddress)) {
    throw "AllowedPaperAddress is required; the relay will not accept arbitrary subscribers."
}

$listenIp = Resolve-IPv4 $ListenAddress
$paperIp = Resolve-IPv4 $AllowedPaperAddress
$flightIp = Resolve-IPv4 $FlightHost
if (-not (Test-PrivateIPv4 $listenIp)) {
    throw "ListenAddress $listenIp is not loopback, RFC1918, or Tailscale CGNAT space. Refusing a public bind."
}
if (-not (Test-PrivateIPv4 $paperIp)) {
    throw "AllowedPaperAddress $paperIp is not a private or Tailscale IPv4 address."
}
if (-not (Test-PrivateIPv4 $flightIp)) {
    throw "FlightHost $flightIp is not a private IPv4 address."
}
if ($listenIp.Equals($flightIp) -and $ListenPort -eq $FlightPort) {
    throw "The relay listen endpoint and FLIGHT endpoint must be different."
}

Write-Host "JUMP UDP relay validation" -ForegroundColor Cyan
Write-Host "  Paper source allowed: $paperIp"
Write-Host "  Relay listens:        $listenIp`:$ListenPort"
Write-Host "  FLIGHT target:        $flightIp`:$FlightPort"
Write-Host "  Public bind:          disabled"
if ($ValidateOnly) {
    Write-Host "Configuration is valid; no sockets were opened." -ForegroundColor Green
    exit 0
}

$paperSocket = $null
$flightSocket = $null
try {
    $paperSocket = New-Object Net.Sockets.UdpClient(
        (New-Object Net.IPEndPoint($listenIp, $ListenPort)))
    $flightSocket = New-Object Net.Sockets.UdpClient(0)
    $paperSocket.Client.Blocking = $false
    $flightSocket.Client.Blocking = $false
    $flightEndpoint = New-Object Net.IPEndPoint($flightIp, $FlightPort)
    $subscriber = $null
    $subscriberExpires = [DateTime]::MinValue
    $lastFindex = [long]::MinValue
    $forwardedHeartbeats = 0L
    $forwardedFrames = 0L
    $rejectedPackets = 0L
    $nextStatus = [DateTime]::UtcNow.AddSeconds(10)

    Write-Host "Relay running. Stop with Ctrl+C." -ForegroundColor Green
    while ($true) {
        $now = [DateTime]::UtcNow
        if ($subscriber -and $now -gt $subscriberExpires) {
            Write-Host "Paper subscriber expired: $subscriber" -ForegroundColor Yellow
            $subscriber = $null
            $lastFindex = [long]::MinValue
        }

        while ($paperSocket.Available -gt 0) {
            $source = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
            $data = $paperSocket.Receive([ref]$source)
            $heartbeat = Read-Heartbeat $data
            if (-not $source.Address.Equals($paperIp) -or -not $heartbeat) {
                $rejectedPackets++
                continue
            }
            if ($subscriber -and -not (Test-EndpointEqual $subscriber $source)) {
                $rejectedPackets++
                continue
            }
            if ($heartbeat.Findex -lt $lastFindex) {
                $rejectedPackets++
                continue
            }
            $subscriber = New-Object Net.IPEndPoint($source.Address, $source.Port)
            $lastFindex = $heartbeat.Findex
            $effectiveExpiry = [Math]::Min($heartbeat.ExpirySeconds, [double]$SubscriberTimeoutSeconds)
            $subscriberExpires = $now.AddSeconds($effectiveExpiry)
            [void]$flightSocket.Send($data, $data.Length, $flightEndpoint)
            $forwardedHeartbeats++
        }

        while ($flightSocket.Available -gt 0) {
            $source = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
            $data = $flightSocket.Receive([ref]$source)
            if (-not (Test-EndpointEqual $source $flightEndpoint)) {
                $rejectedPackets++
                continue
            }
            if ($subscriber -and [DateTime]::UtcNow -le $subscriberExpires) {
                [void]$paperSocket.Send($data, $data.Length, $subscriber)
                $forwardedFrames++
            }
        }

        if ($now -ge $nextStatus) {
            Write-Host "relay heartbeats=$forwardedHeartbeats frames=$forwardedFrames rejected=$rejectedPackets subscriber=$subscriber"
            $nextStatus = $now.AddSeconds(10)
        }
        Start-Sleep -Milliseconds 2
    }
} finally {
    if ($paperSocket) { $paperSocket.Dispose() }
    if ($flightSocket) { $flightSocket.Dispose() }
    Write-Host "Relay stopped."
}
