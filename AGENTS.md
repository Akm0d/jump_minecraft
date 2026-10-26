# JUMP Minecraft Agent Guide

## Purpose and scope

This repository contains the smallest Paper plugin and Windows client setup
needed to display JUMP's authoritative flight trajectory in Minecraft. Keep
JUMP responsible for flight physics. Minecraft is a renderer/controller target,
not a replacement physics engine.

Do not add world management, terrain or collision feedback, custom landing
zones, launchpads, new flight mechanics, or a custom Minecraft client unless a
future task explicitly changes the scope.

The adjacent `/home/akmod/code/jump_flight` repository is reference material
only. Never modify it from this project. Verify protocol conclusions against
its source rather than relying solely on `analysis.md`.

## Repository map

- `src/main/java/com/jump/minecraft/`: Paper plugin, UDP v1 client, lifecycle,
  coordinate mapping, and player-state restoration.
- `src/main/resources/`: `plugin.yml` and default plugin configuration.
- `src/test/`: JUnit tests, including Paper controller tests.
- `tools/synthetic_flight.py`: deterministic JUMP UDP v1 synthetic sender.
- `tools/test_synthetic_flight.py`: synthetic protocol tests.
- `scripts/install_client.ps1`: idempotent Windows Minecraft/Fabric/Vivecraft
  setup entry point.
- `scripts/flight_udp_relay.ps1`: optional private relay for loopback-bound
  FLIGHT. It is not a control service.
- `tools/test_flight_relay.py`: relay integration test; set `POWERSHELL` when
  PowerShell is not on `PATH`.
- `analysis.md`: source-backed architecture and feasibility investigation.
- `ROADMAP.md`: ordered milestones and the authoritative record of what has and
  has not been verified end to end.
- `README.md`: operator, installation, testing, and recovery instructions.

## Current priority

Follow `ROADMAP.md` in milestone order. The active milestone is **P0 — First
complete Minecraft flight (before visiting the bay)**. The Windows installer
and relay were committed to `main` in `6da9b09`, but neither a successful live
Paper deployment nor a Windows/Vivecraft/SteamVR run has been demonstrated.

Before requesting deployment, confirm the intended source revision, rebuild the
JAR, run all required tests, record its SHA-256, and prepare exact backup and
rollback commands. Then report findings and proposed next steps. Do not skip to
P1/P2 work merely because it can be tested without the live Paper milestone.
Never mark a roadmap checkbox complete without direct evidence, and distinguish
observed results from inferred or previously reported results.

## Required validation

Use Java 25 and the checked-in Gradle wrapper. Production builds must resolve
the real Paper API; never restore or package Paper API stubs.

Run before committing:

```bash
./gradlew clean check jar --no-daemon
python3 -m unittest discover -s tools -p 'test_*.py' -v
git diff --check
```

If PowerShell is installed somewhere nonstandard:

```bash
POWERSHELL=/path/to/pwsh \
  python3 -m unittest -v tools/test_flight_relay.py
```

Also inspect the production JAR before delivery:

```bash
jar tf build/libs/jump-minecraft-0.1.0.jar
sha256sum build/libs/jump-minecraft-0.1.0.jar
```

Paper remains a `compileOnly` dependency. `plugin.yml` must retain a Paper 26.3
compatible `api-version` and the `/jump` command registration.

## Runtime and thread-safety invariants

- UDP parsing and socket I/O run off the Paper main thread.
- All player, world, chunk, teleport, and Bukkit/Paper API operations run on the
  Paper main thread.
- Accept only monotonically ordered JUMP frames and disengage on stale input.
- Preserve JUMP as position- and orientation-authoritative; do not apply Elytra
  or Minecraft flight physics.
- Restore the player's saved gravity, flight, velocity, and related state on
  landed, stale data, reset/preparing, disarm, disconnect, kick, shutdown, or
  any safety disengagement.
- Do not synchronously generate destination chunks during flight.

## Protocol and safety boundaries

The plugin supports JUMP GameModule UDP v1 JSON. Subscription heartbeats contain
only `findex`, `expiresInSeconds`, and `helicopterFlag: false`. Do not fabricate
launch, readiness, calibration, reset, collision, canopy, landing, or hardware
commands.

The optional relay exists only when Paper cannot directly reach a loopback-bound
FLIGHT service. Keep these properties intact:

- bind only to an explicit loopback, RFC1918, or Tailscale CGNAT IPv4 address;
- allow one explicitly configured Paper source address and active source port;
- never bind publicly or expose UDP 1201/12100 through Playit;
- forward valid subscription heartbeats and FLIGHT replies byte-for-byte;
- preserve ordering and subscription expiry; and
- remain separately runnable, stoppable, and optional.

The public Minecraft server address and the private FLIGHT/relay endpoint are
different settings. Never treat a Playit hostname as a FLIGHT endpoint.

## Windows client installer invariants

`scripts/install_client.ps1` is the supported entry point. Keep normal runs
idempotent and preserve unrelated launcher profiles, mods, saves, and account
data. It must:

- use trusted upstream metadata and checksum downloads;
- create only the dedicated `JUMP Minecraft VR (26.3)` game directory/profile;
- leave Microsoft authentication to the official launcher;
- avoid editing `servers.dat` or undocumented auto-connect state;
- support `-DryRun`, `-ValidateOnly`, `-Repair`, and `-NonInteractive`;
- avoid automatic elevation and clearly describe normal installer prompts; and
- report SteamVR/headset readiness without claiming JUMP/Vivecraft tracking
  coexistence is proven.

Current default pins are Minecraft 26.3, Fabric Loader 0.20.1, Fabric Installer
1.1.2, Vivecraft 26.3-1.3.17, and Java 25. Revalidate all pins against official
upstream metadata before changing them.

## Known limitation

Do not claim that Space initiates landing in physical FLIGHT mode. Available
source confirms desktop Space starts a jump, desktop `P` triggers canopy
deployment, and scripted landing needs `setLandingTarget`. The bridge follows
an existing landing sequence if FLIGHT emits one; it does not create one.

## Live-system rules

Do not restart or reconfigure the live Paper/LinuxGSM server, modify its world,
change Tailscale or Playit, start synthetic player movement, or deploy a plugin
without explicit authorization. Do not bypass any JUMP hardware safety
interlock. Keep Unreal and Minecraft mutually exclusive for a flight session.

For an authorized P0 deployment, inspect the target first, preserve any existing
plugin JAR with a timestamped backup, document the exact rollback path, and use
LinuxGSM for a clean stop/start rather than Paper `/reload`. Inspect the actual
startup log and verify command registration before asking an operator to connect.
Never start the synthetic sender until a test player is connected and explicitly
armed. Exercise landed, disarm, stale-stream, and disconnect restoration during
the controlled test, without changing the world or adding gameplay features.

Commit and push only when the user explicitly authorizes it. Preserve unrelated
working-tree changes, use non-destructive Git operations, and report the commit,
remote branch, tests, JAR path, and checksum after delivery.
