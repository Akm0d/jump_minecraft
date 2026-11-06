package com.jump.minecraft;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.entity.Player;

final class StationManager {
    private static final long JUMP_REQUEST_COOLDOWN_NANOS = 2_000_000_000L;
    enum RegistrationResult {
        REGISTERED,
        REFRESHED,
        DUPLICATE,
        CAPACITY
    }

    private final Server server;
    private final MovementConfig movement;
    private final int maxStations;
    private final long registrationTtlNanos;
    private final Consumer<String> log;
    private final ChunkPreloader chunks;
    private final Map<String, StationSession> sessions = new LinkedHashMap<>();

    StationManager(
            Server server,
            MovementConfig movement,
            int maxStations,
            long registrationTtlMillis,
            Consumer<String> log) {
        this(server, movement, maxStations, registrationTtlMillis, ChunkPreloader.loadedOnly(), log);
    }

    StationManager(
            Server server,
            MovementConfig movement,
            int maxStations,
            long registrationTtlMillis,
            ChunkPreloader chunks,
            Consumer<String> log) {
        this.server = server;
        this.movement = movement;
        this.maxStations = maxStations;
        this.registrationTtlNanos = registrationTtlMillis * 1_000_000L;
        this.chunks = chunks;
        this.log = log;
    }

    synchronized RegistrationResult register(StationRegistration registration) {
        sessions.entrySet().removeIf(entry -> !entry.getKey().equals(registration.stationId())
                && entry.getValue().isExpiredAndUnassigned(
                        registration.receivedNanos(), registrationTtlNanos));
        StationSession session = sessions.get(registration.stationId());
        boolean created = session == null;
        if (created) {
            if (sessions.size() >= maxStations) return RegistrationResult.CAPACITY;
            session = new StationSession(
                    registration.stationId(),
                    registration.stationName(),
                    server,
                    movement,
                    chunks,
                    message -> log.accept("[" + registration.stationId() + "] " + message));
            sessions.put(registration.stationId(), session);
        }
        if (!session.register(registration, registrationTtlNanos)) {
            if (created) sessions.remove(registration.stationId());
            return RegistrationResult.DUPLICATE;
        }
        return created ? RegistrationResult.REGISTERED : RegistrationResult.REFRESHED;
    }

    boolean acceptFrame(InetSocketAddress source, byte[] bytes, int length, long receivedNanos) {
        StationSession session = sessionForEndpoint(source, receivedNanos);
        if (session == null) return false;
        session.acceptFrame(bytes, length, receivedNanos);
        return true;
    }

    synchronized List<StationSession> connectedSessions(long nowNanos) {
        return sessions.values().stream()
                .filter(session -> session.connectedEndpoint(nowNanos, registrationTtlNanos) != null)
                .toList();
    }

    synchronized StationSession session(String stationId) {
        return sessions.get(stationId);
    }

    String assignAutomatically(Player player) {
        long now = System.nanoTime();
        List<StationSession> available = availableSessions(now);
        InetSocketAddress playerEndpoint = player.getAddress();
        InetAddress playerAddress = playerEndpoint == null ? null : playerEndpoint.getAddress();
        List<StationSession> addressMatches = playerAddress == null
                ? List.of()
                : available.stream()
                        .filter(session -> {
                            InetSocketAddress endpoint =
                                    session.connectedEndpoint(now, registrationTtlNanos);
                            return endpoint != null && playerAddress.equals(endpoint.getAddress());
                        })
                        .toList();
        if (addressMatches.size() == 1) {
            return assign(addressMatches.getFirst(), player, "matched station network address");
        }
        if (addressMatches.size() > 1) {
            return "ambiguous: " + addressMatches.size() + " unclaimed stations share the player's address";
        }
        long unassignedPlayers = server.getOnlinePlayers().stream()
                .filter(online -> !isPlayerAssigned(online.getUniqueId()))
                .count();
        if (available.size() == 1 && unassignedPlayers == 1) {
            return assign(available.getFirst(), player, "only unclaimed station");
        }
        return available.isEmpty()
                ? "waiting: no connected unclaimed station"
                : "ambiguous: no address match among " + available.size() + " unclaimed stations";
    }

    synchronized String assign(String stationId, Player player) {
        StationSession station = sessions.get(stationId);
        if (station == null) return "unknown station: " + stationId;
        return assign(station, player, "manual override");
    }

    synchronized String assignFirstAvailable(Player player) {
        List<StationSession> available = availableSessions(System.nanoTime());
        if (available.size() != 1) {
            return "expected exactly one unclaimed station, found " + available.size();
        }
        return assign(available.getFirst(), player, "operator selected sole station");
    }

    synchronized String unassignStation(String stationId, String reason) {
        StationSession session = sessions.get(stationId);
        if (session == null) return "unknown station: " + stationId;
        session.unassign(reason);
        return "station " + stationId + " unassigned";
    }

    void unassignAll(String reason) {
        snapshot().forEach(session -> session.unassign(reason));
    }

    void unassign(UUID playerId, String reason) {
        for (StationSession session : snapshot()) {
            if (session.isAssignedTo(playerId)) session.unassign(reason);
        }
    }

    boolean shouldBlockMovement(UUID playerId) {
        return snapshot().stream().anyMatch(session -> session.shouldBlockMovement(playerId));
    }

    boolean isProtected(UUID playerId) {
        return snapshot().stream().anyMatch(session -> session.isProtected(playerId));
    }

    String toggleMode(UUID playerId) {
        return snapshot().stream()
                .filter(session -> session.isAssignedTo(playerId))
                .findFirst()
                .map(session -> session.toggleMode(playerId))
                .orElse("not assigned to a JUMP station");
    }

    JumpRequestResult requestJump(UUID playerId, long nowNanos) {
        return snapshot().stream()
                .filter(session -> session.isAssignedTo(playerId))
                .findFirst()
                .map(session -> session.requestJump(
                        playerId, nowNanos, JUMP_REQUEST_COOLDOWN_NANOS))
                .orElseGet(() -> JumpRequestResult.rejected("not assigned to a JUMP station"));
    }

    void tick() {
        snapshot().forEach(StationSession::tick);
        for (Player player : server.getOnlinePlayers()) {
            if (isPlayerAssigned(player.getUniqueId())) continue;
            String result = assignAutomatically(player);
            if (result.startsWith("assigned ")) {
                player.sendMessage("JUMP assignment: " + result);
            }
        }
    }

    List<String> statusLines() {
        long now = System.nanoTime();
        return snapshot().stream()
                .map(session -> session.status(now, registrationTtlNanos))
                .toList();
    }

    synchronized List<String> stationIds() {
        return List.copyOf(sessions.keySet());
    }

    void close() {
        snapshot().forEach(session -> session.unassign("plugin shutdown"));
        chunks.close();
    }

    private synchronized StationSession sessionForEndpoint(InetSocketAddress source, long nowNanos) {
        return sessions.values().stream()
                .filter(session -> session.matchesEndpoint(source, nowNanos, registrationTtlNanos))
                .findFirst()
                .orElse(null);
    }

    private List<StationSession> availableSessions(long nowNanos) {
        return connectedSessions(nowNanos).stream()
                .filter(session -> !session.isAssigned())
                .toList();
    }

    private boolean isPlayerAssigned(UUID playerId) {
        return snapshot().stream().anyMatch(session -> session.isAssignedTo(playerId));
    }

    private String assign(StationSession station, Player player, String reason) {
        if (station.isActive() && !station.isAssignedTo(player.getUniqueId())) {
            return "station " + station.id() + " is in an active flight";
        }
        for (StationSession existing : snapshot()) {
            if (existing != station && existing.isAssignedTo(player.getUniqueId())) {
                existing.unassign("player reassigned to " + station.id());
            }
        }
        station.assign(player);
        String message = "assigned " + player.getName() + " to " + station.id() + " (" + reason + ")";
        log.accept(message);
        return message;
    }

    private synchronized List<StationSession> snapshot() {
        return new ArrayList<>(sessions.values());
    }
}
