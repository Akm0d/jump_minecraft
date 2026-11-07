# JUMP Minecraft Roadmap

## Goal

Make Minecraft a plug-and-play renderer for two JUMP bays and a development
workstation on one Paper server. JUMP FLIGHT stays authoritative. Normal use
must not require station IP configuration, a relay window, or `/jump arm`.

Automated evidence is not live-server, Windows launcher, VR, or physical-flight
evidence. Those boundaries remain explicit below.

Facility evidence reported from the November 6, 2026 session is also recorded
explicitly: the actual workstation decoded more than 66,000 v2 frames with zero
rejections, registered and assigned its player automatically, and reached
`LANDED`. That session also demonstrated the prior failure mode: an unloaded
destination chunk ended control, after which gravity caused the player to fall
and die.

## Verified development baseline (2026-11-06)

- [x] Derive GameFrame v2's 368-byte default-aligned MSVC x64 wire layout from
  `jump_flight` source without modifying that repository.
- [x] Decode and validate v1 JSON and v2 binary frames, all eight lifecycle
  states, optional trailing JSON, malformed input, bounded JSON nesting, and
  ordered frame delivery.
- [x] Maintain independent sessions, endpoints, ordering, controllers, mapping
  origins, stale handling, restoration, and diagnostics for a bounded arbitrary
  number of stations. Controller tests cover landed/reset/stale/stream-reset,
  disconnect, world-change, unloaded-chunk, and rejected-teleport restoration.
- [x] Exercise two concurrent 1,000-frame streams without cross-station state.
- [x] Implement outbound station registration, address/port refresh, duplicate
  identity rejection, capacity bounds, private-address checks, and DHCP takeover
  after expiry.
- [x] Implement deterministic address-first automatic player assignment,
  unambiguous single-station fallback, ambiguity refusal, manual diagnostic
  override, late-registration retry, reconnect, and repeat-flight behavior.
- [x] Convert the Windows relay into a self-registering background bridge and
  integrate persistent identity, Task Scheduler lifecycle, validate/repair,
  upgrade, and recoverable uninstall into the existing installer.
- [x] Parse both Windows scripts successfully with Windows PowerShell 5.1 on
  the target Alienware host.
- [x] Execute the bridge's loopback UDP integration suite under Windows
  PowerShell 5.1: registration, exact heartbeat/frame forwarding, source
  isolation, invalid-control rejection, lease expiry, and loopback-only FLIGHT.
- [x] Execute the isolated installer idempotency test under Windows PowerShell
  5.1 with Task Scheduler cmdlets mocked: stable identity, repeat task replace,
  stop-before-upgrade/uninstall, validation, recoverable uninstall, and
  preservation of unrelated profiles and data all passed.
- [ ] Run an actual Windows installer install/re-run/repair/validate/uninstall
  cycle and confirm Task Scheduler recovery after logoff/reboot.

## P0 — Release artifact and remote deployment preparation

- [x] Run the final Java 25 `clean check jar`, Python suite, and
  `git diff --check`; inspect the production JAR. SHA-256:
  `eba3379e9829b792a112181aef892bb53e8ee5c7c18ba747eb497164c77854a0`.
- [x] Inspect the Alienware server read-only: actual directory, Paper/Java
  versions, installed plugin/config/hash, processes, listener, logs, and startup
  mechanisms.
- [x] Confirm historical failure was v2 bytes reaching a v1-only decoder rather
  than basic Minecraft or relay connectivity.
- [x] Identify and exercise the server's documented clean Paper start/stop
  procedure: PowerShell starts `java -Xms2G -jar .\paper.jar nogui` and the
  Paper console `stop` command performs the clean shutdown. No persistent
  service or scheduled task exists.
- [x] With explicit authorization, preserve timestamped plugin/config backups,
  install the verified JAR, and perform a clean Paper start.
- [x] Inspect startup logs, plugin registration, UDP 12101 listener, and
  `/jump status`; register a no-player validation station and accept one
  source-verified 368-byte v2 `LANDED` frame with zero rejections. Preserve an
  exact file/config rollback and leave Paper cleanly stopped.

## P1 — Controlled desktop integration

- [x] On the actual facility workstation, verify registration, the local FLIGHT
  v2 subscription, more than 66,000 accepted frames with zero rejections, and a
  complete lifecycle through `LANDED`.
- [x] Connect a desktop Minecraft client and verify automatic assignment with
  no command.
- [ ] With a connected player and explicit test authorization, use deterministic
  v1 and v2 streams to observe trajectory, yaw/pitch, and landed restoration.
- [ ] Exercise stale stream, bridge restart, address/port change, disconnect,
  reconnect, world change, unloaded chunk, and repeat flight.

## P0 — Flight experience reliability (automated implementation)

- [x] Keep assigned players protected through preparing, flight, landing,
  stale telemetry, rendering stalls, and repeat flights; restore original state
  only on disconnect, unassignment, kick, or plugin shutdown.
- [x] Prepare destination and trajectory look-ahead chunks asynchronously with
  bounded per-station/global queues and tickets. Hold the last safe rendered
  position until ready, retain newest telemetry, and resume automatically.
- [x] Bound large trajectory catch-up and preserve independent station state.
- [x] Add explicit MINECRAFT/FLIGHT/FLIGHT DESKTOP modes. The two FLIGHT modes
  block Minecraft physics; MINECRAFT restores captured ordinary controls.
- [x] Compile a Minecraft 26.3 Fabric companion with F1 cycling and survival
  HUD suppression; integrate its build, Fabric API, and upgrade/validation into
  the existing installer.
- [x] Route validated, station-specific desktop WASD/mouse and edge-triggered
  Space input through Paper and the bridge to FLIGHT's guarded test interface.
- [x] Keep the Windows bridge registering and retrying when local FLIGHT UDP is
  temporarily unavailable; report a degraded health state instead of exiting.
- [x] Add Paper block-shape collision observations, inverse coordinate mapping,
  and an explicitly disabled/rate-limited `pushplayer` bridge path.
- [ ] Exercise the new chunk protection, three modes, HUD, bridge recovery, and repeat
  landing behavior on Windows with a real Minecraft client.

## P2 — Three-station concurrency

- [ ] Register Bay 1, Bay 2, and the development workstation simultaneously.
- [ ] Connect corresponding clients over the LAN and verify address-first player
  assignment. If the server sees a proxy/NAT address, retain manual override
  until a station-identity client component is justified by observed evidence.
- [ ] Run overlapping deterministic flights and demonstrate that no packet,
  lifecycle transition, stale event, or disconnect alters another player.
- [ ] Restart one bridge and change one workstation address without editing
  Paper configuration or swapping active assignments.

## P3 — Vivecraft and facility validation

- [ ] Verify official launcher authentication, Minecraft 26.3, Fabric 0.20.1,
  Vivecraft 26.3-1.3.17, Java 25, and SteamVR startup on Windows.
- [ ] Verify headset look versus server-controlled travel, movement smoothness,
  roll limitations, and Vivecraft locomotion behavior.
- [ ] Coordinate with a JUMP technician; keep Unreal and Minecraft mutually
  exclusive and preserve all JUMP interlocks and calibration behavior.
- [ ] Observe real v2 preparing/jumping/flying/canopy/landed transitions and
  validate movement before attempting a physical launch.
- [ ] Perform a controlled physical flight only with facility approval. Do not
  fabricate launch, landing, canopy, reset, readiness, or hardware commands.
- [ ] Determine actual physical landing ownership. Space-to-land remains
  unconfirmed; do not claim completion without an observed `landed` sequence.

## P4 — Collision feedback (disabled pending validation)

- [ ] Validate Minecraft contact points, normals, axes, scale, and timing in an
  isolated desktop FLIGHT simulation.
- [ ] Derive and validate `pushplayer` force units against FLIGHT/Unreal behavior;
  do not infer a force from contact geometry alone.
- [x] Implement explicitly enabled, rate-limited, per-station feedback with
  bridge validation and a hard refusal on production port 1201.
- [ ] Validate it in isolated desktop FLIGHT before enabling it; never enable
  feedback for physical-mode FLIGHT without separate facility approval.

## Optional visual enhancements

- [ ] After baseline desktop and VR validation, evaluate Sodium, Iris,
  Complementary Unbound, and Distant Horizons individually for Minecraft 26.3,
  Vivecraft compatibility, and VR frame rate.
- [ ] Confirm the intended projects and compatible releases before considering
  Better Blending or Jodkas Smooth Terrain; neither was verified for 26.3.
- [ ] Keep these out of the installer and Paper server until separately tested
  and approved.

## Future work outside current scope

- Custom landing zones, launchpads, world management, or Minecraft-owned flight
  physics.
- A client identification mechanism beyond the current companion unless real
  network topology proves deterministic server-side assignment insufficient.
- Public/cloud telemetry paths, databases, containers, or changes to FLIGHT.

## Working rules

Follow `AGENTS.md`: build against the real Paper API; never package stubs; keep
network parsing off the Paper main thread and entity/world operations on it;
retain protected presentation across transient safety stalls and restore saved
state on explicit release paths; never start synthetic movement before a
connected player is explicitly controlled for testing; never expose FLIGHT
traffic publicly; and do not deploy, restart, commit, or push without explicit
authorization.
