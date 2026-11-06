# JUMP Minecraft Flight Bridge

JumpMinecraft lets several JUMP FLIGHT workstations drive independent players
on one Paper server. FLIGHT remains authoritative for trajectory and lifecycle;
Minecraft samples the newest telemetry at server tick rate and renders it.
The only control request implemented is desktop FLIGHT's existing loopback-only
`forceJumpEvent`; physical Navigator/OpenVR restrictions remain in FLIGHT. The
bridge never sends calibration, canopy, landing, reset, collision-force, or
hardware commands.

The integration supports GameModule JSON v1 and the Windows x64 binary v2
format used by current FLIGHT installations. It does not require changes to
FLIGHT or its Unreal configuration.

## Architecture

Each Windows workstation runs one lightweight station bridge installed as a
current-user scheduled task:

```text
FLIGHT 127.0.0.1:1201
        ^  subscription/reply relay + validated desktop jump request
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
configuration. Its zero-configuration defaults connect Minecraft to
`10.1.10.158:25565` and register the station with Paper at
`10.1.10.158:12101/UDP`. Both addresses remain installer parameters for future
network changes. The bridge registers a persistent random station ID, refreshes
its current address and source port, relays only valid passive subscription
heartbeats to local FLIGHT, and forwards FLIGHT replies while the Paper lease is
live. Configured hostnames are re-resolved periodically to tolerate address
changes.

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
  preload-lookahead-steps: 6
  max-chunks-per-station: 12
  max-concurrent-chunk-loads: 8
  max-retained-chunks: 48
collision:
  observation-enabled: true
  feedback-enabled: false
  max-distance-blocks: 32.0
```

Allow UDP 12101 only on the trusted LAN/private overlay. Do not publish it
through Playit or a public firewall rule. Playit, if used, carries Minecraft
client traffic only. Paper no longer stores a per-station FLIGHT address.
Registration, v1, and v2 trailing JSON share a fixed nesting limit so a
malformed LAN datagram cannot exhaust the registry thread's stack.

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
on the first flying frame. Destination and look-ahead chunks are prepared with
Paper's asynchronous chunk API under per-station and global limits. If a chunk
is late, the player holds the last rendered position under FLIGHT protection,
newest telemetry continues buffering, and rendering resumes automatically.
Large moves use bounded catch-up steps; no arbitrary distant chunk is
synchronously generated on the tick thread.

An assigned player remains in protected presentation through preparing,
flight, landing, stale telemetry, and chunk delays. FLIGHT mode disables
gravity/collision and blocks ordinary player movement. FREE mode restores local
movement while retaining damage and interaction protection. Disconnect, kick,
unassignment, and plugin shutdown restore the captured pre-JUMP player state.

## Windows JUMP technician installation

From a downloaded or cloned repository, run in ordinary Windows PowerShell:

```powershell
.\scripts\install_client.ps1
```

The zero-argument defaults use Minecraft server `10.1.10.158:25565`, Paper
registration `10.1.10.158:12101/UDP`, and local FLIGHT `127.0.0.1:1201`.
Optional parameters can change the server addresses for a future private
deployment, but routine JUMP technician setup requires no address entry.

The installer:

- verifies official upstream metadata and downloaded checksums;
- finds or installs a private Java 25 JDK;
- installs Fabric, Fabric API, Vivecraft, and the JUMP Fabric companion only in
  `JUMP Minecraft VR (26.3)`;
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

JUMP technician diagnostics are intentionally short:

- Minecraft problem: launcher/profile or server connection fails.
- FLIGHT problem: the bridge registers but receives no local frames.
- Bridge problem: the scheduled task is absent/stopped or its health is failed.
- Registration/Paper problem: health remains waiting and `/jump stations`
  shows no station.
- Assignment problem: the station is healthy but reports unassigned/ambiguous.

`-ValidateOnly` returns a failure when the bridge task is absent or stopped, or
when its health is failed, unreadable, or more than 60 seconds old. A newly
started task may briefly report `not reported yet` before its first health write.
`degraded` means Paper registration is alive but local FLIGHT is temporarily
unavailable; the bridge keeps retrying rather than terminating.

## Dedicated client controls

The companion is built and installed by `install_client.ps1` into the isolated
JUMP profile. It uses Fabric key mappings and plugin messaging—no global
keyboard hook or extra Windows helper.

- **F1** toggles FLIGHT and FREE modes after Paper validates the player's
  station assignment.
- **F2** requests `forceJumpEvent` only for an assigned FLIGHT-mode player with
  fresh `PREPARING` or `LANDED` telemetry. Paper and the bridge both suppress
  duplicate presses; the bridge emits the final UDP datagram from loopback.

Within the dedicated profile, F1/F2 are removed from their conflicting vanilla
bindings. Hearts, hunger, armor, experience, hotbar, held-item tooltip, and the
crosshair are hidden; menus, chat, and operator messages remain available.

## Terrain collision observation

Paper performs bounded, observation-only block-shape ray traces along rendered
movement. Diagnostics retain the candidate Minecraft contact point/normal and
their inverse-mapped FLIGHT-space values. With scale `s`, a contact point is
translated from the Minecraft anchor, divided by `s`, inverse-rotated by the
trajectory alignment, then added to the FLIGHT origin. Normals are inverse-
rotated without scaling and normalized. Bounded rays sample the player's Paper
bounding box at lower, center, upper, and lateral points against actual block
collision shapes for terrain and structures. This is diagnostic sampling, not
a continuous physics solver.

`collision.feedback-enabled` remains `false`. No `pushplayer` force is produced
or sent. FLIGHT collision-response units and force construction require an
isolated desktop-simulation validation before feedback can be enabled.

### Optional visual enhancements

Sodium, Iris, Complementary Unbound, and Distant Horizons are possible future
client-side enhancements, but none are installed or managed by the JUMP
installer. Better Blending and Jodkas Smooth Terrain were not verified for
Minecraft 26.3. Evaluate any optional mod individually for Vivecraft
compatibility and VR frame rate only after the baseline facility tests pass;
do not add server components without separate approval.

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
- The Fabric companion compiles against Minecraft 26.3/Fabric Loader 0.20.1/
  Fabric API 0.165.0+26.3, but its key handling and HUD behavior have not yet
  been exercised with Vivecraft on facility hardware.

See [analysis.md](analysis.md) for source evidence, [ROADMAP.md](ROADMAP.md) for
the distinction between automated evidence and remaining live validation, and
[DEPLOYMENT.md](DEPLOYMENT.md) for the inspected Alienware state and rollback.
