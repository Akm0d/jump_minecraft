# JUMP Minecraft Roadmap

## Goal

Let a person wearing the JUMP wingsuit fly through an existing Minecraft Java world using **JUMP FLIGHT as the authoritative physics and flight-state source**. Paper moves one explicitly armed Minecraft player; a Windows Minecraft/Vivecraft client renders the experience in the headset.

**Priority:** prove a complete, repeatable JUMP-bay flight with minimal operator setup. Do not expand into new worlds, collision meshes, launch zones, or custom VR rendering before that works.

## Current baseline (2026-10-26)

- [x] Analyze JUMP GameModule's UDP subscription and flight-state stream; document the v1 JSON protocol and known physical-mode landing uncertainty in `analysis.md`.
- [x] Implement the Paper 26.3 / Java 25 bridge: single-player arming, relative position/orientation, ordered frames, stale-stream disengagement, and player-state restoration.
- [x] Implement a deterministic synthetic FLIGHT sender and unit/controller tests.
- [x] Build against the **real** Paper API `26.3.build.224-stable` with the checked-in Gradle wrapper; reported validation: 9 JUnit tests, 36 core assertions, and 4 Python tests passing.
- [x] Implement Windows `scripts/install_client.ps1` and optional private `scripts/flight_udp_relay.ps1`; reported PowerShell parsing, metadata dry-run, and relay integration tests passing.
- [x] Confirm the Windows scripts and latest documentation are committed/pushed to `main` (`6da9b09`).
- [ ] Deploy the plugin to the live Paper server on `begula` and verify startup. **Not performed yet.**
- [ ] Test the installer and actual Minecraft/Vivecraft/SteamVR startup on a Windows machine. **Not performed yet.**

A green build is not evidence of successful live flight or headset compatibility.

## P0 — First complete Minecraft flight (before visiting the bay)

**Objective:** reproduce the entire UDP-to-player movement path on `begula` with a real Minecraft client.

- [ ] Confirm the intended source revision, JAR checksum, and build/test results; preserve a previous-plugin backup and rollback procedure.
- [ ] With explicit approval, install the JAR in `/home/mcserver/serverfiles/plugins/`, restart LinuxGSM/Paper, and inspect startup logs for plugin/API errors.
- [ ] Verify `/jump status`, `/jump arm`, and `/jump disarm`, including operator-only `jump.control` permissions on the public server.
- [ ] Confirm Minecraft Java 26.3 can join the server at the actual assigned Playit address. If Playit is not configured yet, configure/test the Minecraft TCP tunnel independently of FLIGHT.
- [ ] Connect a test player, arm intentionally, run `tools/synthetic_flight.py` against `127.0.0.1:1201`, and observe position/orientation throughout a flight.
- [ ] Verify restoration after `landed`, disarm, a stopped sender/stale stream, and client disconnect; confirm the public visitor cannot arm another player.
- [ ] Inspect chunk-boundary/discontinuity handling and practical movement smoothness; record logs/TPS and observed behavior.

**Exit criteria:** Paper starts cleanly, one authorized client visibly follows synthetic FLIGHT frames, all expected disengagement paths restore controls, and a written reproducible test/rollback procedure exists.

## P1 — One-command Windows VR client

**Objective:** a technician can prepare a Windows bay workstation without hand-installing each component.

- [x] Commit/review the Windows scripts and README from the Codex worktree without accidentally including generated files, caches, credentials, or binaries (`6da9b09`).
- [ ] Run `install_client.ps1 -DryRun` and then a real installation on Windows x64; verify official launcher sign-in, dedicated Fabric profile, Vivecraft version, private Java runtime if needed, and repeat-run idempotency.
- [ ] Determine the real Playit hostname and decide whether to persist it as a documented/repository default; currently `-MinecraftServer` is **mandatory**, so bare `install_client.ps1` is not yet zero-argument.
- [ ] Verify ordinary Minecraft connection and synthetic movement before enabling Vivecraft/SteamVR.
- [ ] Verify Minecraft 26.3 + Fabric Loader 0.20.1 + Vivecraft 26.3-1.3.17 launch in VR, with headset look independent from JUMP-controlled travel.
- [ ] Check motion judder, server yaw/pitch versus headset pose, banking/roll limitations, VR comfort, and unintended Vivecraft locomotion.
- [ ] Produce a technician quick-start: install once, sign in, select JUMP profile, connect, arm; include precise failure recovery.

**Exit criteria:** a repeatable Windows setup and a synthetic flight visible in the headset. Do not claim successful VR operation based on PowerShell parsing or metadata checks alone.

## P2 — Real JUMP bay integration

**Objective:** substitute physical FLIGHT output for the synthetic sender while preserving JUMP's hardware safeguards.

- [ ] Determine whether the production FLIGHT GameModule can safely emit **v1 JSON** and whether it binds an approved private address or only localhost; verify against source and live configuration.
- [ ] Establish an authorized **private** UDP path from `begula` to Windows. If FLIGHT is localhost-only, use the optional bidirectional relay on a specific private/Tailscale IPv4 and configure Paper to subscribe to its port (default relay port `12100`); never use Playit for FLIGHT UDP.
- [ ] Validate the real relay on Windows with allowed source IP, firewall/Tailscale policy, heartbeat registration, frame freshness, and stop/disconnect behavior. Do not assume synthetic loopback tests prove remote UDP routing.
- [ ] Confirm the physical bay can run FLIGHT and Vivecraft together without device/runtime contention, and that Unreal is not required for safety-critical readiness or calibration. Never bypass interlocks.
- [ ] With facility/operator approval, establish readiness, arm exactly one Minecraft player, perform a controlled physical launch, and verify `preparing -> jumping -> flying` and position/orientation tracking.
- [ ] Observe technician landing input and FLIGHT logs. **Physical-mode Space-to-land is not confirmed**; do not claim landing until `setLandingTarget`/equivalent behavior and resulting lifecycle are observed.
- [ ] Verify landed/stale/unexpected-loss restoration, operator disarm, physical emergency procedures, and shutdown behavior.
- [ ] Record exact bay startup/shutdown commands, network addresses/ports (without secrets), software versions, logs, and repeatable outcomes.

**Exit criteria:** one controlled physical JUMP flight drives Minecraft VR from takeoff through a safely terminated session. If automatic landing is unavailable, record that limitation explicitly and keep the test confined to an operator-approved termination procedure.

## P3 — Reliability and operator ergonomics

Begin only after P2 establishes a working end-to-end path.

- [ ] Consolidate install, validate, relay start/stop, server connection, and health checks into a small operator workflow with clear status/error messages.
- [ ] Measure packet latency, frame age, Paper TPS, client visual interpolation, and flight displacement at representative speeds; improve the narrowest proven bottleneck.
- [ ] Handle re-arming/reconnecting, relay/network interruption, repeat flights, and restart recovery deterministically.
- [ ] Decide whether a dedicated client-side VR orientation/interpolation component is actually needed for roll/banking or smoothness. Do not build one speculatively.
- [ ] Add lightweight release/build automation and tested Windows regression coverage for installer/relay changes.

**Exit criteria:** an operator can reproduce multiple sessions without code edits or manual network debugging, with known recovery steps.

## P4 — Environment integration (future, not MVP)

- [ ] Evaluate Minecraft block/terrain collision representation and the cost of getting collision geometry into JUMP.
- [ ] Evaluate safe collision-force feedback through existing JUMP interfaces; keep JUMP physics authoritative and review hardware safety implications.
- [ ] Define configurable Minecraft launch markers, landing zones, proximity triggers, and mapping back to JUMP-world coordinates.
- [ ] Investigate intended landing-target API/ownership and how zones interact with `setLandingTarget` only after the actual bay landing behavior is understood.
- [ ] Assess long-distance chunk preloading, world boundaries, terrain scaling, and multiplayer/spectator requirements based on observations.

## Architecture and non-goals

- **Authoritative flight:** JUMP FLIGHT; nominal physics 1000 Hz, GameModule frames ~90 Hz.
- **Bridge:** Paper 26.3 plugin on `begula`, applies newest valid frame on the ~20 Hz game tick, with one explicitly armed player.
- **Rendering:** Windows Minecraft Java + Fabric/Vivecraft + SteamVR; Minecraft rendering is not the physics authority.
- **Minecraft networking:** Playit carries public Minecraft client TCP traffic only; it is not a JUMP telemetry path.
- **FLIGHT networking:** private UDP 1201 direct, or private relay listener 12100 to localhost FLIGHT 1201 when necessary.
- **Not MVP:** custom Minecraft worlds, meshes/collision feedback, landing zones, automated physical landing commands, custom client mod, Elytra physics, changes to `jump_flight`.

## Working rules

Follow `AGENTS.md`: build against real Paper API; run Gradle/Python validations; never send synthetic movement to an unarmed live player; back up before deployment; do not change the JUMP source or physical safety mechanisms. Live-server/network changes require explicit approval. Report what was actually tested versus inferred, and update milestone checkboxes only after evidence exists.
