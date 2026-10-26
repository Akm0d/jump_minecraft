package com.jump.minecraft;

import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public final class FlightController {
    private final Server server;
    private final OrderedFrameStore frames;
    private final FlightLifecycle lifecycle = new FlightLifecycle();
    private final Consumer<String> log;
    private final long staleAfterNanos;
    private final double scale;
    private final double headingOffset;
    private final double maxStepBlocks;

    private UUID playerId;
    private Location minecraftOrigin;
    private CoordinateMapper mapper;
    private PlayerControlSnapshot controlSnapshot;
    private long lastAppliedFindex = Long.MIN_VALUE;
    private String lastStopReason = "never armed";
    private UUID teleportingPlayer;

    public FlightController(
            Server server,
            OrderedFrameStore frames,
            MovementConfig movement,
            Consumer<String> log) {
        this.server = server;
        this.frames = frames;
        this.staleAfterNanos = movement.staleAfterMillis() * 1_000_000L;
        this.scale = movement.blocksPerMeter();
        this.headingOffset = movement.headingOffsetDegrees();
        this.maxStepBlocks = movement.maxStepBlocks();
        this.log = log;
    }

    public synchronized void arm(Player player) {
        restore("re-armed");
        playerId = player.getUniqueId();
        lifecycle.arm();
        lastAppliedFindex = Long.MIN_VALUE;
        lastStopReason = "armed, waiting for jumping/flying";
        player.sendMessage("JUMP armed. Waiting for a physical flight transition.");
    }

    public synchronized void disarm(String reason) {
        restore(reason);
    }

    public synchronized boolean isDesignated(UUID id) {
        return playerId != null && playerId.equals(id);
    }

    public synchronized boolean shouldBlockMovement(UUID id) {
        return controlSnapshot != null && playerId != null && playerId.equals(id)
                && !id.equals(teleportingPlayer);
    }

    public synchronized String status() {
        OrderedFrameStore.Snapshot snapshot = frames.latest();
        String frame = snapshot == null ? "none" : snapshot.frame().state() + " #" + snapshot.frame().findex();
        return "player=" + (playerId == null ? "none" : playerId)
                + ", armed=" + lifecycle.isArmed()
                + ", active=" + lifecycle.isActive()
                + ", latest=" + frame
                + ", note=" + lastStopReason;
    }

    public synchronized void tick() {
        if (!lifecycle.isArmed() || playerId == null) return;
        Player player = server.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            restore("player disconnected");
            return;
        }
        OrderedFrameStore.Snapshot snapshot = frames.latest();
        if (snapshot == null) return;
        if (frames.isStale(System.nanoTime(), staleAfterNanos)) {
            if (controlSnapshot != null) restore("UDP frames became stale");
            return;
        }
        FlightFrame frame = snapshot.frame();
        if (frame.findex() == lastAppliedFindex) return;
        lastAppliedFindex = frame.findex();
        SessionDecision decision = lifecycle.observe(frame.state());
        switch (decision) {
            case WAIT -> { }
            case CAPTURE_MINECRAFT_ORIGIN -> captureMinecraftOrigin(player);
            case START_TRAJECTORY -> {
                if (minecraftOrigin == null) captureMinecraftOrigin(player);
                mapper = new CoordinateMapper(
                        vector(minecraftOrigin), minecraftOrigin.getYaw(), frame, scale, headingOffset);
                apply(player, frame, true);
            }
            case APPLY_TRAJECTORY -> apply(player, frame, false);
            case RESTORE_LANDED -> {
                if (mapper != null) apply(player, frame, true);
                restore("JUMP reported landed");
            }
            case RESTORE_RESET -> restore("JUMP returned to preparing/reset");
        }
    }

    private void captureMinecraftOrigin(Player player) {
        minecraftOrigin = player.getLocation().clone();
        controlSnapshot = PlayerControlSnapshot.capture(player);
        controlSnapshot.engage(player);
        lastStopReason = "launch detected";
    }

    private void apply(Player player, FlightFrame frame, boolean permitLargeStep) {
        if (mapper == null || minecraftOrigin == null) return;
        MappedPose pose = mapper.map(frame);
        World world = minecraftOrigin.getWorld();
        if (world == null) {
            restore("launch world is unavailable");
            return;
        }
        Location destination = new Location(world, pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch());
        Location current = player.getLocation();
        if (!permitLargeStep && !current.getWorld().equals(world)) {
            restore("player changed worlds");
            return;
        }
        if (!permitLargeStep && current.distance(destination) > maxStepBlocks) {
            restore("movement discontinuity exceeded " + maxStepBlocks + " blocks");
            return;
        }
        int chunkX = destination.getBlockX() >> 4;
        int chunkZ = destination.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            restore("destination chunk is not loaded");
            return;
        }
        player.setVelocity(new Vector());
        player.setFallDistance(0.0f);
        boolean moved;
        teleportingPlayer = player.getUniqueId();
        try {
            moved = player.teleport(destination);
        } finally {
            teleportingPlayer = null;
        }
        if (!moved) restore("Paper rejected player teleport");
    }

    private void restore(String reason) {
        Player player = playerId == null ? null : server.getPlayer(playerId);
        if (player != null && controlSnapshot != null) {
            controlSnapshot.restore(player);
            player.sendMessage("JUMP control released: " + reason);
        }
        if (playerId != null) log.accept("JUMP session stopped for " + playerId + ": " + reason);
        lifecycle.disarm();
        playerId = null;
        minecraftOrigin = null;
        mapper = null;
        controlSnapshot = null;
        teleportingPlayer = null;
        lastAppliedFindex = Long.MIN_VALUE;
        lastStopReason = reason;
    }

    private static Vec3 vector(Location location) {
        return new Vec3(location.getX(), location.getY(), location.getZ());
    }
}
