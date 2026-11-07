package com.jump.minecraft;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

final class StationSession {
    private final String stationId;
    private final OrderedFrameStore frames = new OrderedFrameStore();
    private final FlightController controller;

    private String stationName;
    private InetSocketAddress endpoint;
    private long lastRegistrationNanos;
    private long acceptedFrames;
    private long rejectedFrames;
    private String lastProtocol = "none";
    private String lastError = "waiting for registration";

    StationSession(
            String stationId,
            String stationName,
            Server server,
            MovementConfig movement,
            ChunkPreloader chunks,
            Consumer<StationControlRequest> controlSender,
            Consumer<String> log) {
        this.stationId = stationId;
        this.stationName = stationName;
        controller = new FlightController(
                stationId, server, frames, movement, chunks, controlSender, log);
    }

    synchronized boolean register(StationRegistration registration, long ttlNanos) {
        boolean endpointChanged = endpoint != null && !endpoint.equals(registration.endpoint());
        if (endpointChanged && lastRegistrationNanos + ttlNanos >= registration.receivedNanos()) {
            lastError = "duplicate stationId registration from " + registration.endpoint();
            return false;
        }
        if (endpointChanged) frames.clear();
        stationName = registration.stationName();
        endpoint = registration.endpoint();
        lastRegistrationNanos = registration.receivedNanos();
        lastError = "none";
        return true;
    }

    synchronized InetSocketAddress connectedEndpoint(long nowNanos, long ttlNanos) {
        return endpoint != null && nowNanos - lastRegistrationNanos <= ttlNanos ? endpoint : null;
    }

    synchronized boolean matchesEndpoint(InetSocketAddress source, long nowNanos, long ttlNanos) {
        return source.equals(connectedEndpoint(nowNanos, ttlNanos));
    }

    synchronized boolean isExpiredAndUnassigned(long nowNanos, long ttlNanos) {
        return endpoint != null
                && nowNanos - lastRegistrationNanos > ttlNanos
                && controller.assignedPlayerId() == null;
    }

    void acceptFrame(byte[] bytes, int length, long receivedNanos) {
        try {
            String protocol = FlightFrameDecoder.protocol(bytes, length);
            FlightFrame frame = FlightFrameDecoder.decode(bytes, length);
            synchronized (this) {
                if (frames.offer(frame, receivedNanos)) {
                    acceptedFrames++;
                    lastProtocol = protocol;
                    lastError = "none";
                } else {
                    rejectedFrames++;
                    lastError = "out-of-order frame #" + frame.findex();
                }
            }
        } catch (IllegalArgumentException error) {
            synchronized (this) {
                rejectedFrames++;
                lastError = error.getMessage();
            }
        }
    }

    void assign(Player player) {
        controller.arm(player);
    }

    void unassign(String reason) {
        controller.disarm(reason);
    }

    void tick() {
        controller.tick();
    }

    boolean isAssigned() {
        return controller.assignedPlayerId() != null;
    }

    boolean isAssignedTo(UUID playerId) {
        return controller.isDesignated(playerId);
    }

    boolean isActive() {
        return controller.isActive();
    }

    boolean shouldBlockMovement(UUID playerId) {
        return controller.shouldBlockMovement(playerId);
    }

    boolean isProtected(UUID playerId) {
        return controller.isProtected(playerId);
    }

    String toggleMode(UUID playerId) {
        return controller.toggleMode(playerId);
    }

    String setMode(UUID playerId, PlayerControlMode mode) {
        return controller.setMode(playerId, mode);
    }

    PlayerControlMode mode() {
        return controller.mode();
    }

    StationControlRequest desktopInput(UUID playerId, JumpDesktopInput input) {
        return controller.acceptsDesktopInput(playerId)
                ? StationControlRequest.desktopInput(stationId, input)
                : null;
    }

    String id() {
        return stationId;
    }

    synchronized String name() {
        return stationName;
    }

    OrderedFrameStore frames() {
        return frames;
    }

    synchronized String status(long nowNanos, long ttlNanos) {
        boolean connected = endpoint != null && nowNanos - lastRegistrationNanos <= ttlNanos;
        return stationId + " (" + stationName + "): connected=" + connected
                + ", endpoint=" + (endpoint == null ? "none" : endpoint)
                + ", accepted=" + acceptedFrames
                + ", rejected=" + rejectedFrames
                + ", lastProtocol=" + lastProtocol
                + ", lastError=" + lastError
                + ", " + controller.status();
    }
}
