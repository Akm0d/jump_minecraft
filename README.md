# JUMP Minecraft — Technician Startup

## Minecraft server

Open PowerShell:

```powershell
cd C:\Users\admin\jump_minecraft
java -Xms2G -Xmx4G -jar .\paper.jar --nogui
```

Keep the server console open.

## JUMP workstation — FLIGHT and Minecraft

**1. Start the FLIGHT engine**

Launch the existing `DesktopFlight` engine normally.

**2. Start the station bridge**

Open a separate PowerShell window:

```powershell
& "$env:LOCALAPPDATA\JUMP Minecraft\bridge\flight_udp_relay.ps1" -ConfigPath "$env:LOCALAPPDATA\JUMP Minecraft\station-bridge.json"
```

Keep this window open. Stop the bridge with `Ctrl+C`.

**3. Launch Minecraft**

Open the official Minecraft Launcher and select:

`JUMP Minecraft VR (26.3)`

Connect to:

`10.1.10.25:25565`

**4. Verify connection**

On the Paper console:

```text
jump stations
```

Expected: the workstation is connected, a player is assigned, and accepted telemetry frames increase.

## Controls

| Mode | Controls |
|---|---|
| MINECRAFT | Normal WASD, mouse, Space |
| FLIGHT | FLIGHT-driven movement, local mouse-look |
| FLIGHT DESKTOP | Local mouse-look; WASD/Space require isolated desktop simulation |

Press F1 to cycle modes.

## Shutdown

1. Disconnect Minecraft.
2. Stop the station bridge with `Ctrl+C`.
3. Stop Paper by typing `stop` in its console.
4. Shut down FLIGHT through its normal procedure.

