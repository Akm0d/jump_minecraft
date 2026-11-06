package com.jump.minecraft;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class FlightController {
    private final String stationId;
    private final Server server;
    private final OrderedFrameStore frames;
    private final FlightLifecycle lifecycle = new FlightLifecycle();
    private final ChunkPreloader chunks;
    private final TerrainCollisionObserver collisionObserver;
    private final Consumer<String> log;
    private final long staleAfterNanos;
    private final double scale;
    private final double headingOffset;
    private final double maxStepBlocks;
    private final int preloadLookaheadSteps;
    private final int maxChunksPerStation;

    private UUID playerId;
    private Location minecraftOrigin;
    private CoordinateMapper mapper;
    private PlayerControlSnapshot controlSnapshot;
    private PlayerControlMode mode = PlayerControlMode.FLIGHT;
    private long lastObservedFindex = Long.MIN_VALUE;
    private long lastRenderedFindex = Long.MIN_VALUE;
    private FlightFrame pendingFrame;
    private SessionDecision pendingDecision;
    private Location previousMappedTarget;
    private String lastNote = "never assigned";
    private UUID teleportingPlayer;
    private long collisionObservations;
    private TerrainCollisionObservation lastCollision;

    public FlightController(
            Server server,
            OrderedFrameStore frames,
            MovementConfig movement,
            Consumer<String> log) {
        this("test", server, frames, movement, ChunkPreloader.loadedOnly(), log);
    }

    FlightController(
            String stationId,
            Server server,
            OrderedFrameStore frames,
            MovementConfig movement,
            ChunkPreloader chunks,
            Consumer<String> log) {
        this.stationId = stationId;
        this.server = server;
        this.frames = frames;
        this.chunks = chunks;
        this.staleAfterNanos = movement.staleAfterMillis() * 1_000_000L;
        this.scale = movement.blocksPerMeter();
        this.headingOffset = movement.headingOffsetDegrees();
        this.maxStepBlocks = movement.maxStepBlocks();
        this.preloadLookaheadSteps = movement.preloadLookaheadSteps();
        this.maxChunksPerStation = movement.maxChunksPerStation();
        this.collisionObserver = new TerrainCollisionObserver(
                movement.collisionObservationEnabled(), movement.collisionMaxDistanceBlocks());
        this.log = log;
    }

    public synchronized void arm(Player player) {
        if (playerId != null && playerId.equals(player.getUniqueId()) && controlSnapshot != null) {
            controlSnapshot.enforce(player, mode);
            return;
        }
        unassign("re-assigned", false);
        playerId = player.getUniqueId();
        controlSnapshot = PlayerControlSnapshot.capture(player);
        mode = PlayerControlMode.FLIGHT;
        controlSnapshot.engageFlight(player);
        resetTrajectory("assigned, waiting for FLIGHT telemetry");
        player.sendMessage("JUMP station ready in FLIGHT mode.");
    }

    public synchronized void disarm(String reason) {
        unassign(reason, true);
    }

    public synchronized UUID assignedPlayerId() {
        return playerId;
    }

    public synchronized boolean isActive() {
        return lifecycle.isActive();
    }

    public synchronized boolean isDesignated(UUID id) {
        return playerId != null && playerId.equals(id);
    }

    public synchronized boolean isProtected(UUID id) {
        return isDesignated(id) && controlSnapshot != null;
    }

    public synchronized boolean shouldBlockMovement(UUID id) {
        return mode == PlayerControlMode.FLIGHT
                && controlSnapshot != null
                && playerId != null
                && playerId.equals(id)
                && !id.equals(teleportingPlayer);
    }

    public synchronized String toggleMode(UUID id) {
        if (!isDesignated(id)) return "not assigned to a JUMP station";
        Player player = server.getPlayer(id);
        if (player == null || !player.isOnline() || controlSnapshot == null) {
            return "assigned player is not online";
        }
        mode = mode.toggled();
        chunks.release(stationId);
        resetTrajectory("mode changed to " + mode);
        if (mode == PlayerControlMode.FLIGHT) {
            controlSnapshot.engageFlight(player);
            player.sendMessage("JUMP FLIGHT mode: telemetry controls movement.");
        } else {
            controlSnapshot.engageFree(player);
            player.sendMessage("JUMP FREE mode: local movement enabled; protection remains active.");
        }
        return "mode=" + mode;
    }

    public synchronized boolean readyForJumpRequest(UUID id) {
        if (!isDesignated(id) || mode != PlayerControlMode.FLIGHT) return false;
        OrderedFrameStore.Snapshot snapshot = frames.latest();
        if (snapshot == null || frames.isStale(System.nanoTime(), staleAfterNanos)) return false;
        return snapshot.frame().state() == FlightState.PREPARING
                || snapshot.frame().state() == FlightState.LANDED;
    }

    public synchronized String status() {
        OrderedFrameStore.Snapshot snapshot = frames.latest();
        String frame = snapshot == null ? "none" : snapshot.frame().state() + " #" + snapshot.frame().findex();
        return "player=" + (playerId == null ? "none" : playerId)
                + ", mode=" + mode
                + ", active=" + lifecycle.isActive()
                + ", latest=" + frame
                + ", rendered=#" + (lastRenderedFindex == Long.MIN_VALUE ? "none" : lastRenderedFindex)
                + ", collisionObservations=" + collisionObservations
                + ", lastCollision=" + (lastCollision == null
                        ? "none"
                        : "#" + lastCollision.findex() + " at " + lastCollision.minecraftPoint())
                + ", note=" + lastNote;
    }

    public synchronized void tick() {
        if (!lifecycle.isArmed() || playerId == null || controlSnapshot == null) return;
        Player player = server.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            unassign("player disconnected", false);
            return;
        }
        controlSnapshot.enforce(player, mode);

        OrderedFrameStore.Snapshot snapshot = frames.latest();
        if (snapshot == null) {
            lastNote = "waiting for telemetry; protected presentation retained";
            return;
        }
        if (frames.isStale(System.nanoTime(), staleAfterNanos)) {
            lastNote = "telemetry stale; holding protected position";
            return;
        }

        FlightFrame frame = snapshot.frame();
        if (frame.findex() != lastObservedFindex) observe(player, frame);
        if (mode == PlayerControlMode.FLIGHT) renderPending(player);
    }

    private void observe(Player player, FlightFrame frame) {
        lastObservedFindex = frame.findex();
        SessionDecision decision = lifecycle.observe(frame.state());
        if (mode == PlayerControlMode.FREE) {
            if (decision == SessionDecision.RESTORE_LANDED
                    || decision == SessionDecision.RESTORE_RESET) {
                resetTrajectory("FLIGHT lifecycle reset while in FREE mode");
            } else {
                lastNote = "FREE mode; telemetry buffered at #" + frame.findex();
            }
            return;
        }
        switch (decision) {
            case WAIT -> {
                pendingFrame = null;
                pendingDecision = null;
                lastNote = "waiting in " + frame.state();
            }
            case CAPTURE_MINECRAFT_ORIGIN -> {
                captureMinecraftOrigin(player);
                pendingFrame = null;
                pendingDecision = null;
            }
            case START_TRAJECTORY -> {
                if (minecraftOrigin == null) captureMinecraftOrigin(player);
                mapper = new CoordinateMapper(
                        vector(minecraftOrigin), minecraftOrigin.getYaw(), frame, scale, headingOffset);
                pendingFrame = frame;
                pendingDecision = decision;
            }
            case APPLY_TRAJECTORY, RESTORE_LANDED -> {
                pendingFrame = frame;
                pendingDecision = decision;
            }
            case RESTORE_RESET -> resetTrajectory("FLIGHT returned to preparing/reset");
        }
    }

    private void renderPending(Player player) {
        if (pendingFrame == null || pendingDecision == null) return;
        if (mapper == null && pendingDecision != SessionDecision.RESTORE_LANDED) return;
        if (!apply(player, pendingFrame)) return;
        lastRenderedFindex = pendingFrame.findex();
        boolean landed = pendingDecision == SessionDecision.RESTORE_LANDED;
        pendingFrame = null;
        pendingDecision = null;
        if (landed) resetTrajectory("FLIGHT reported landed; protected FLIGHT mode retained");
    }

    private void captureMinecraftOrigin(Player player) {
        minecraftOrigin = player.getLocation().clone();
        previousMappedTarget = null;
        lastNote = "launch detected; protected presentation active";
    }

    private boolean apply(Player player, FlightFrame frame) {
        if (mapper == null || minecraftOrigin == null) return true;
        Location current = player.getLocation();
        World world = minecraftOrigin.getWorld();
        if (world == null) {
            lastNote = "launch world unavailable; holding protected position";
            return false;
        }
        if (current.getWorld() != world) {
            chunks.release(stationId);
            minecraftOrigin = current.clone();
            mapper = new CoordinateMapper(vector(current), current.getYaw(), frame, scale, headingOffset);
            previousMappedTarget = null;
            lastNote = "world changed; trajectory safely rebased";
            return true;
        }

        MappedPose pose = mapper.map(frame);
        Location mapped = new Location(world, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
        if (!withinWorld(world, mapped)) {
            lastNote = "mapped position outside world boundary; holding protected position";
            return false;
        }

        Location step = ChunkPath.boundedStep(current, mapped, maxStepBlocks);
        List<ChunkPosition> upcoming = ChunkPath.upcoming(
                current, step, previousMappedTarget, mapped, preloadLookaheadSteps, maxChunksPerStation);
        ChunkPosition required = ChunkPosition.of(step.getX(), step.getZ());
        if (!chunks.prepare(stationId, world, required, upcoming)) {
            previousMappedTarget = mapped;
            lastNote = "destination chunk preparing; holding protected position";
            return false;
        }

        boolean catchingUp = current.distance(mapped) > maxStepBlocks;
        TerrainCollisionObservation collision = collisionObserver.observe(
                world, player, current, step, mapper, frame.findex());
        if (collision != null) {
            lastCollision = collision;
            collisionObservations++;
        }
        player.setVelocity(new Vector());
        player.setFallDistance(0.0f);
        boolean moved;
        teleportingPlayer = player.getUniqueId();
        try {
            moved = player.teleport(step);
        } finally {
            teleportingPlayer = null;
        }
        previousMappedTarget = mapped;
        if (!moved) {
            lastNote = "Paper rejected teleport; holding protected position";
            return false;
        }
        lastNote = catchingUp
                ? "rendering bounded catch-up toward frame #" + frame.findex()
                : "rendered frame #" + frame.findex();
        return !catchingUp;
    }

    private boolean withinWorld(World world, Location location) {
        if (!Double.isFinite(location.getX())
                || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ())) return false;
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        if (maxHeight > minHeight
                && (location.getY() < minHeight || location.getY() >= maxHeight)) return false;
        return world.getWorldBorder() == null || world.getWorldBorder().isInside(location);
    }

    private void resetTrajectory(String reason) {
        lifecycle.arm();
        minecraftOrigin = null;
        mapper = null;
        pendingFrame = null;
        pendingDecision = null;
        previousMappedTarget = null;
        lastObservedFindex = Long.MIN_VALUE;
        chunks.release(stationId);
        lastNote = reason;
    }

    private void unassign(String reason, boolean notify) {
        Player player = playerId == null ? null : server.getPlayer(playerId);
        if (player != null && controlSnapshot != null) {
            controlSnapshot.restore(player);
            if (notify) player.sendMessage("JUMP assignment released: " + reason);
        }
        if (playerId != null) log.accept("JUMP assignment released for " + playerId + ": " + reason);
        chunks.release(stationId);
        lifecycle.disarm();
        playerId = null;
        minecraftOrigin = null;
        mapper = null;
        controlSnapshot = null;
        teleportingPlayer = null;
        pendingFrame = null;
        pendingDecision = null;
        previousMappedTarget = null;
        lastObservedFindex = Long.MIN_VALUE;
        lastRenderedFindex = Long.MIN_VALUE;
        mode = PlayerControlMode.FLIGHT;
        lastNote = reason;
    }

    private static Vec3 vector(Location location) {
        return new Vec3(location.getX(), location.getY(), location.getZ());
    }
}
