# JUMP Minecraft Flight Bridge

JumpMinecraft lets several JUMP FLIGHT workstations drive independent players
on one Paper server. FLIGHT remains authoritative for trajectory and lifecycle;
Minecraft samples the newest telemetry at server tick rate and renders it. The
bridge never sends launch, readiness, calibration, canopy, landing, reset,
collision, or hardware commands.

The integration supports GameModule JSON v1 and the Windows x64 binary v2
format used by current FLIGHT installations. It does not require changes to
FLIGHT or its Unreal configuration.

## Architecture

Each Windows workstation runs one lightweight station bridge installed as a
current-user scheduled task:

```text
FLIGHT 127.0.0.1:1201
        ^  byte-for-byte subscription/reply relay
        |
station bridge -- outbound registration --> Paper UDP :12101
                                             |
                                      StationManager
                              +--------------+--------------+
                              |              |              |
                           Bay 1          Bay 2           Dev rig
                              |              |              |
                         Player A        Player B        Player C
```

The station bridge initiates all LAN traffic, so Paper needs no workstation IP
configuration. It registers a persistent random station ID, refreshes its
current address and source port, relays only valid passive subscription
heartbeats to local FLIGHT, and forwards FLIGHT replies while the Paper lease is
live. Hostnames are re-resolved periodically to tolerate address changes.

Paper owns one UDP registry thread and one tick task for all stations. Each
station has independent endpoint, frame ordering, newest-frame buffer,
diagnostics, controller, coordinate origin, timeout, assignment, and saved
player state. Bukkit/Paper entity and world operations occur only on the main
thread. Packet I/O and v1/v2 decoding occur off-thread.

## Protocol compatibility

The v2 decoder implements the native default-aligned MSVC x64 layout emitted by
`jump_flight/src/server/GameModule.cpp`:

| Offset | Size | Field |
|---:|---:|---|
| 0 | 8 | four little-endian `int16`: type, version, binsize, jsonsize |
| 8 | 8 | little-endian `uint64` frame index |
| 16 | 4 | `int32` flight state |
| 20 | 36 | player position, forward, up (`Vec3f` each) |
| 56 | 288 | eight trackers, each position/forward/up |
| 344 | 1 | tracking-valid `bool` |
| 345 | 3 | alignment padding |
| 348 | 8 | speed and HUD angle-of-attack floats |
| 356 | 8 | exit point and next exit point `int32` |
| 364 | 4 | tail padding; binary structure size is 368 bytes |
| 368 | variable | optional NUL-terminated UTF-8 JSON object |

Packets are rejected for the wrong type/version/size, truncation or trailing
bytes, invalid state or boolean, non-finite values, invalid JSON termination or
encoding, and non-monotonic frame indices. States 0 through 7 map to
not-initialized, preparing, jumping, flying, pre-deployed, deployed,
canopy-flying, and landed.
JSON v1 remains supported and uses the same normalized `FlightFrame` model.

## Paper installation and configuration

Build with Java 25 and the checked-in wrapper:

```bash
GRADLE_USER_HOME=/tmp/jump-gradle ./gradlew clean check jar --no-daemon
python3 -m unittest discover -s tools -p 'test_*.py' -v
git diff --check
jar tf build/libs/jump-minecraft-0.1.0.jar
sha256sum build/libs/jump-minecraft-0.1.0.jar
```

On Windows, the Python suite exercises the bridge directly when PowerShell is
available. The installer filesystem/task lifecycle can also be checked without
touching real launcher profiles or scheduled tasks:

```powershell
.\tools\test_installer_idempotency.ps1
```

Paper supplies the compile-only API. The production JAR must not contain Paper
or Bukkit classes.

Install the verified JAR in Paper's `plugins` directory only during an approved
maintenance window. Preserve the previous JAR and configuration first, then use
the server's actual clean stop/start mechanism. Never use `/reload`.

Default generated configuration:

```yaml
flight:
  stale-after-millis: 1000
registration:
  bind-host: "0.0.0.0"
  port: 12101
  heartbeat-interval-millis: 1000
  subscription-expiry-seconds: 3.0
  socket-timeout-millis: 200
  station-ttl-millis: 5000
  max-stations: 16
movement:
  blocks-per-meter: 1.0
  heading-offset-degrees: 0.0
  max-step-blocks: 20.0
```

Allow UDP 12101 only on the trusted LAN/private overlay. Do not publish it
through Playit or a public firewall rule. Playit, if used, carries Minecraft
client traffic only. Paper no longer stores a per-station FLIGHT address.

## Automatic player assignment

When a player joins, Paper first matches the player's connection address to an
unclaimed registered station. If exactly one station and one unassigned player
remain, it uses that unambiguous fallback. Multiple stations behind the same
address remain unassigned instead of being guessed; an operator can diagnose or
override the mapping:

```text
/jump status
/jump stations
/jump assign <station-id> <online-player>
/jump disarm [station-id]
```

`/jump arm [player]` remains only as a compatibility/troubleshooting command
when exactly one station is available. It is not part of normal operation.

Assignments survive `landed`, stale telemetry, and repeat flights so the same
station is ready again without a command. An active flight is never silently
reassigned. Disconnect, kick, manual disarm, and plugin shutdown clear the
assignment and restore saved player state.

The controller captures the Minecraft anchor on `jumping` and the FLIGHT origin
on the first flying frame. It restores gravity, flight flags, velocity, and
related state on every disengagement path. It rejects unsafe movement jumps,
world changes, and unloaded destination chunks rather than synchronously
generating chunks.

## Windows technician installation

From a downloaded or cloned repository, run in ordinary Windows PowerShell:

```powershell
.\scripts\install_client.ps1
```

The zero-argument defaults use Minecraft/Paper hostname `jump`, local FLIGHT at
`127.0.0.1:1201`, and Paper registration UDP 12101. Optional parameters can
change hostnames for a different private deployment, but routine bay setup does
not require IP addresses.

The installer:

- verifies official upstream metadata and downloaded checksums;
- finds or installs Java 25;
- installs Fabric and Vivecraft only in `JUMP Minecraft VR (26.3)`;
- preserves unrelated launcher profiles, mods, saves, and credentials;
- creates a persistent station identity and bridge configuration;
- installs and starts `JUMP Minecraft Station Bridge` as a current-user Task
  Scheduler task with automatic restart; and
- leaves Microsoft authentication to the official launcher.

The task starts at user logon; no PowerShell window needs to remain open.
Configuration and health are under `%LOCALAPPDATA%\JUMP Minecraft`.
Rerunning the installer performs an in-place upgrade: it stops the owned bridge
task, refreshes pinned artifacts and configuration while preserving the station
identity, replaces the task definition, and starts the new bridge instance.

Maintenance modes:

```powershell
.\scripts\install_client.ps1 -ValidateOnly -NonInteractive
.\scripts\install_client.ps1 -Repair -NonInteractive
.\scripts\install_client.ps1 -DryRun -NonInteractive
.\scripts\install_client.ps1 -Uninstall -NonInteractive
```

Uninstall unregisters the task, removes only the dedicated launcher profile,
and moves JUMP-owned files into a timestamped recoverable `uninstalled`
directory. It preserves shared launcher files, Fabric libraries, Java,
credentials, and unrelated saves/profiles.

Technician diagnostics are intentionally short:

- Minecraft problem: launcher/profile or server connection fails.
- FLIGHT problem: the bridge registers but receives no local frames.
- Bridge problem: the scheduled task is absent/stopped or its health is failed.
- Registration/Paper problem: health remains waiting and `/jump stations`
  shows no station.
- Assignment problem: the station is healthy but reports unassigned/ambiguous.

## Safety and limitations

- FLIGHT and Unreal configuration are not modified. Run Unreal and Minecraft
  mutually exclusively for a flight session.
- The bridge requires FLIGHT on IPv4 loopback, accepts Paper only on loopback,
  RFC1918, or Tailscale CGNAT IPv4 space, and never opens a public listener.
- Physical-mode Space-to-land remains unconfirmed. The plugin follows an
  existing landing sequence but does not create one.
- Minecraft has no normal player roll; forward-vector yaw/pitch is rendered and
  the up vector is retained for validation.
- 90 Hz telemetry is newest-frame sampled at Paper's tick rate rather than
  replayed.
- Vivecraft/SteamVR coexistence with physical JUMP tracking still requires a
  controlled facility test.

See [analysis.md](analysis.md) for source evidence, [ROADMAP.md](ROADMAP.md) for
the distinction between automated evidence and remaining live validation, and
[DEPLOYMENT.md](DEPLOYMENT.md) for the inspected Alienware state and rollback.
