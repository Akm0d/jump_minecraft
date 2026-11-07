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
    [switch]$EnableDesktopInput,
    [switch]$EnableCollisionFeedback,
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

function Read-ControlRequest([byte[]]$Data) {
    try {
        $message = ([Text.Encoding]::UTF8.GetString($Data) | ConvertFrom-Json)
        $requestId = [string]$message.requestId
        $parsedRequestId = [guid]::Empty
        if ([int]$message.jumpControl -ne 1 -or
            [string]$message.stationId -ne $StationId -or
            -not [guid]::TryParse($requestId, [ref]$parsedRequestId)) {
            return $null
        }
        $action = [string]$message.action
        if ($action -eq "desktopInput" -and $EnableDesktopInput) {
            $inputMessage = $message.input
            if (-not $inputMessage -or $inputMessage.jump -isnot [bool]) { return $null }
            $forward = Read-BoundedNumber $inputMessage "forward" 1.0
            $right = Read-BoundedNumber $inputMessage "right" 1.0
            $mouseX = Read-BoundedNumber $inputMessage "mouseX" 1000.0
            $lookPitch = Read-BoundedNumber $inputMessage "lookPitch" 85.0
            if ($null -eq $forward -or $null -eq $right -or
                $null -eq $mouseX -or $null -eq $lookPitch) { return $null }
            $flightMessage = [ordered]@{
                desktopTestInput = [ordered]@{
                    forward = $forward; right = $right; vertical = 0.0
                    mouseX = $mouseX; mouseY = 0.0; lookPitch = $lookPitch
                    jump = [bool]$inputMessage.jump; reset = $false
                    deploy = $false; sprint = $false
                }
            } | ConvertTo-Json -Compress -Depth 4
            return [pscustomobject]@{
                RequestId = $parsedRequestId.ToString()
                Action = $action
                FlightData = [Text.Encoding]::UTF8.GetBytes($flightMessage)
            }
        }
        if ($action -eq "pushPlayer" -and $EnableCollisionFeedback) {
            $collision = $message.collision
            $response = Read-Vector $collision.response 100.0 $false
            $hitPoint = Read-Vector $collision.hitPoint 1000000.0 $false
            $hitNormal = Read-Vector $collision.hitNormal 1.0 $true
            if ($null -eq $response -or $null -eq $hitPoint -or $null -eq $hitNormal) { return $null }
            $flightMessage = [ordered]@{
                pushplayer = [ordered]@{
                    pos = $response
                    rot = [ordered]@{ x = 0.0; y = 0.0; z = 0.0 }
                    hitPt = $hitPoint
                    hitN = $hitNormal
                }
            } | ConvertTo-Json -Compress -Depth 4
            return [pscustomobject]@{
                RequestId = $parsedRequestId.ToString()
                Action = $action
                FlightData = [Text.Encoding]::UTF8.GetBytes($flightMessage)
            }
        }
        return $null
    } catch {
        return $null
    }
}

function Read-BoundedNumber($Object, [string]$Name, [double]$Bound) {
    if ($null -eq $Object -or $null -eq $Object.PSObject.Properties[$Name]) { return $null }
    try { $value = [double]$Object.$Name } catch { return $null }
    if ([double]::IsNaN($value) -or [double]::IsInfinity($value) -or [Math]::Abs($value) -gt $Bound) {
        return $null
    }
    return $value
}

function Read-Vector($Object, [double]$Bound, [bool]$RequireUnit) {
    $x = Read-BoundedNumber $Object "x" $Bound
    $y = Read-BoundedNumber $Object "y" $Bound
    $z = Read-BoundedNumber $Object "z" $Bound
    if ($null -eq $x -or $null -eq $y -or $null -eq $z) { return $null }
    if ($RequireUnit) {
        $length = [Math]::Sqrt($x * $x + $y * $y + $z * $z)
        if ($length -lt 0.99 -or $length -gt 1.01) { return $null }
    }
    return [ordered]@{ x = $x; y = $y; z = $z }
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

function Send-FlightDatagram(
    [Net.Sockets.UdpClient]$Socket,
    [byte[]]$Data,
    [Net.IPEndPoint]$Endpoint
) {
    try {
        [void]$Socket.Send($Data, $Data.Length, $Endpoint)
        return $null
    } catch [Net.Sockets.SocketException] {
        # An offline loopback UDP peer may surface as WSAECONNRESET on either
        # the next send or receive. This is a recoverable FLIGHT availability state.
        return $_.Exception.Message
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
    $desktopInputProperty = $configuration.PSObject.Properties["enableDesktopInput"]
    if (-not $PSBoundParameters.ContainsKey("EnableDesktopInput") -and $desktopInputProperty) {
        $EnableDesktopInput = [bool]$desktopInputProperty.Value
    }
    $collisionFeedbackProperty = $configuration.PSObject.Properties["enableCollisionFeedback"]
    if (-not $PSBoundParameters.ContainsKey("EnableCollisionFeedback") -and $collisionFeedbackProperty) {
        $EnableCollisionFeedback = [bool]$collisionFeedbackProperty.Value
    }
    if (-not $HealthPath -and $configuration.healthPath) { $HealthPath = [string]$configuration.healthPath }
}

if ([string]::IsNullOrWhiteSpace($StationId) -or $StationId -notmatch '^[A-Za-z0-9._-]{1,64}$') {
    throw "StationId is required and must contain 1-64 letters, digits, dots, underscores, or hyphens."
}
if ([string]::IsNullOrWhiteSpace($StationName) -or $StationName.Length -gt 80) {
    throw "StationName must contain 1-80 characters."
}
if ($EnableCollisionFeedback -and -not $EnableDesktopInput) {
    throw "Collision feedback requires explicitly enabled desktop input."
}
if (($EnableDesktopInput -or $EnableCollisionFeedback) -and $FlightPort -eq 1201) {
    throw "Desktop input and collision feedback require an isolated FLIGHT port, never production port 1201."
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
Write-Host "  Desktop input:  $EnableDesktopInput"
Write-Host "  Collision:      $EnableCollisionFeedback"
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
    $flightBind = New-Object Net.IPEndPoint([Net.IPAddress]::Loopback, 0)
    $flightSocket = New-Object Net.Sockets.UdpClient($flightBind)
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
    $controls = 0L
    $rejected = 0L
    $lastControlRequestId = ""
    $desktopInputActive = $false
    $lastDesktopInputUtc = [DateTime]::MinValue
    $flightUnavailable = $false
    $lastFlightError = "none"
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
            $control = Read-ControlRequest $data
            if ($control) {
                if ($control.RequestId -eq $lastControlRequestId) {
                    $rejected++
                    continue
                }
                $sendError = Send-FlightDatagram $flightSocket $control.FlightData $flightEndpoint
                if ($sendError) {
                    $flightUnavailable = $true
                    $lastFlightError = $sendError
                    $rejected++
                    continue
                }
                $lastControlRequestId = $control.RequestId
                if ($control.Action -eq "desktopInput") {
                    $desktopInputActive = $true
                    $lastDesktopInputUtc = $now
                }
                $controls++
                continue
            }
            $heartbeat = Read-Heartbeat $data
            if (-not $heartbeat -or $heartbeat.Findex -lt $lastHeartbeatFindex) {
                $rejected++
                continue
            }
            $lastHeartbeatFindex = $heartbeat.Findex
            $paperLeaseExpires = $now.AddSeconds($heartbeat.ExpirySeconds)
            $sendError = Send-FlightDatagram $flightSocket $data $flightEndpoint
            if ($sendError) {
                $flightUnavailable = $true
                $lastFlightError = $sendError
                continue
            }
            $heartbeats++
        }

        try {
            while ($flightSocket.Available -gt 0) {
                $source = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
                $data = $flightSocket.Receive([ref]$source)
                if (-not (Test-EndpointEqual $source $flightEndpoint)) {
                    $rejected++
                    continue
                }
                $flightUnavailable = $false
                $lastFlightError = "none"
                if ([DateTime]::UtcNow -le $paperLeaseExpires) {
                    [void]$paperSocket.Send($data, $data.Length, $paperEndpoint)
                    $frames++
                }
            }
        } catch [Net.Sockets.SocketException] {
            # Windows reports ICMP port-unreachable as WSAECONNRESET on a later UDP read.
            # Local FLIGHT may be stopped temporarily; keep Paper registration alive and retry.
            $flightUnavailable = $true
            $lastFlightError = $_.Exception.Message
        }

        if ($desktopInputActive -and $now -ge $lastDesktopInputUtc.AddMilliseconds(350)) {
            $neutralJson = '{"desktopTestInput":{"forward":0.0,"right":0.0,"vertical":0.0,"mouseX":0.0,"mouseY":0.0,"lookPitch":0.0,"jump":false,"reset":false,"deploy":false,"sprint":false}}'
            $neutral = [Text.Encoding]::UTF8.GetBytes($neutralJson)
            [void](Send-FlightDatagram $flightSocket $neutral $flightEndpoint)
            $desktopInputActive = $false
        }

        if ($now -ge $nextStatus) {
            $state = if ($registrationStatus -in @("registered", "refreshed")) {
                if ($flightUnavailable) { "degraded" }
                elseif ($heartbeats -gt 0) { "healthy" }
                else { "registered" }
            } else {
                "waiting"
            }
            $detail = "registrations=$registrations status=$registrationStatus heartbeats=$heartbeats frames=$frames controls=$controls rejected=$rejected flightError=$lastFlightError"
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
    if ($flightSocket -and $desktopInputActive) {
        $neutral = [Text.Encoding]::UTF8.GetBytes('{"desktopTestInput":{"forward":0.0,"right":0.0,"vertical":0.0,"mouseX":0.0,"mouseY":0.0,"lookPitch":0.0,"jump":false,"reset":false,"deploy":false,"sprint":false}}')
        [void](Send-FlightDatagram $flightSocket $neutral $flightEndpoint)
    }
    if ($paperSocket) { $paperSocket.Dispose() }
    if ($flightSocket) { $flightSocket.Dispose() }
    if (-not $failed) { Save-Health "stopped" "Bridge process stopped." }
    Write-Host "Station bridge stopped."
}
