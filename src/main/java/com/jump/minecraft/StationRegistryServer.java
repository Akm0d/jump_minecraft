package com.jump.minecraft;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class StationRegistryServer implements AutoCloseable {
    private static final int MAX_DATAGRAM = 65_507;

    private final StationNetworkConfig config;
    private final StationManager stations;
    private final Consumer<String> info;
    private final Consumer<String> warning;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong heartbeatSequence = new AtomicLong();

    private volatile DatagramSocket socket;
    private volatile Thread thread;
    private volatile long registrations;
    private volatile long rejected;
    private volatile long unknownFrames;
    private volatile String lastError = "not started";

    StationRegistryServer(
            StationNetworkConfig config,
            StationManager stations,
            Consumer<String> info,
            Consumer<String> warning) {
        this.config = config;
        this.stations = stations;
        this.info = info;
        this.warning = warning;
    }

    void start() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("registry already running");
        InetAddress bindAddress = InetAddress.getByName(config.bindHost());
        DatagramSocket opened = new DatagramSocket(new InetSocketAddress(bindAddress, config.port()));
        opened.setSoTimeout(config.socketTimeoutMillis());
        socket = opened;
        thread = Thread.ofPlatform()
                .name("jump-station-registry")
                .daemon(true)
                .uncaughtExceptionHandler((ignored, error) -> recordError("registry thread terminated: " + error))
                .start(() -> run(opened));
        lastError = "none";
        info.accept("JUMP station registry listening on " + opened.getLocalSocketAddress());
    }

    String status() {
        DatagramSocket current = socket;
        return "local=" + (current == null ? "closed" : current.getLocalSocketAddress())
                + ", registrations=" + registrations
                + ", rejected=" + rejected
                + ", unknownFrames=" + unknownFrames
                + ", lastError=" + lastError;
    }

    boolean sendControl(StationControlRequest request) {
        DatagramSocket current = socket;
        StationSession session = stations.session(request.stationId());
        InetSocketAddress endpoint = session == null ? null : session.connectedEndpoint(
                System.nanoTime(), config.registrationTtlMillis() * 1_000_000L);
        if (current == null || current.isClosed() || endpoint == null) return false;
        byte[] payload = request.toJson().getBytes(StandardCharsets.UTF_8);
        try {
            synchronized (current) {
                current.send(new DatagramPacket(payload, payload.length, endpoint));
            }
            return true;
        } catch (IOException error) {
            recordError("control request to " + request.stationId() + " failed: " + error.getMessage());
            return false;
        }
    }

    private void run(DatagramSocket activeSocket) {
        byte[] buffer = new byte[MAX_DATAGRAM];
        long nextHeartbeat = 0L;
        while (running.get()) {
            long now = System.nanoTime();
            if (now >= nextHeartbeat) {
                sendHeartbeats(activeSocket, now);
                nextHeartbeat = now + config.heartbeatIntervalMillis() * 1_000_000L;
            }
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                activeSocket.receive(packet);
                handle(activeSocket, packet, System.nanoTime());
            } catch (SocketTimeoutException ignored) {
                // Timeout keeps registration expiry, heartbeat, and shutdown responsive.
            } catch (IOException error) {
                if (running.get()) {
                    recordError("registry receive failed: " + error.getMessage());
                    running.set(false);
                }
            }
        }
    }

    private void handle(DatagramSocket activeSocket, DatagramPacket packet, long receivedNanos) {
        InetSocketAddress source = new InetSocketAddress(packet.getAddress(), packet.getPort());
        if (!PrivateAddresses.isPrivate(packet.getAddress())) {
            rejected++;
            recordError("rejected public station source " + source);
            return;
        }
        try {
            StationRegistration registration = null;
            if (firstNonWhitespace(packet.getData(), packet.getLength()) == '{') {
                registration = StationRegistration.decodeOrNull(
                        packet.getData(), packet.getLength(), source, receivedNanos);
            }
            if (registration != null) {
                StationManager.RegistrationResult result = stations.register(registration);
                if (result == StationManager.RegistrationResult.DUPLICATE
                        || result == StationManager.RegistrationResult.CAPACITY) {
                    rejected++;
                    sendAck(activeSocket, source, registration.stationId(), result);
                    return;
                }
                registrations++;
                sendAck(activeSocket, source, registration.stationId(), result);
                sendHeartbeat(activeSocket, source);
                return;
            }
            if (!stations.acceptFrame(
                    source, packet.getData(), packet.getLength(), receivedNanos)) {
                unknownFrames++;
            }
        } catch (IllegalArgumentException | IOException error) {
            rejected++;
            recordError("rejected station datagram from " + source + ": " + error.getMessage());
        }
    }

    private void sendHeartbeats(DatagramSocket activeSocket, long nowNanos) {
        for (StationSession session : stations.connectedSessions(nowNanos)) {
            InetSocketAddress endpoint = session.connectedEndpoint(
                    nowNanos, config.registrationTtlMillis() * 1_000_000L);
            if (endpoint == null) continue;
            try {
                sendHeartbeat(activeSocket, endpoint);
            } catch (IOException error) {
                recordError("heartbeat to " + session.id() + " failed: " + error.getMessage());
            }
        }
    }

    private void sendHeartbeat(DatagramSocket activeSocket, InetSocketAddress endpoint) throws IOException {
        long baseMicros = System.currentTimeMillis() * 1_000L;
        long sequence = heartbeatSequence.updateAndGet(previous -> Math.max(previous + 1L, baseMicros));
        byte[] payload = JumpProtocol.heartbeat(sequence, config.subscriptionExpirySeconds());
        activeSocket.send(new DatagramPacket(payload, payload.length, endpoint));
    }

    private void sendAck(
            DatagramSocket activeSocket,
            InetSocketAddress endpoint,
            String stationId,
            StationManager.RegistrationResult result) throws IOException {
        String json = "{\"jumpStationAck\":1,\"stationId\":\""
                + stationId + "\",\"status\":\"" + result.name().toLowerCase() + "\"}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        activeSocket.send(new DatagramPacket(payload, payload.length, endpoint));
    }

    private static char firstNonWhitespace(byte[] bytes, int length) {
        for (int index = 0; index < length; index++) {
            if (!Character.isWhitespace(bytes[index] & 0xff)) return (char) bytes[index];
        }
        return 0;
    }

    private void recordError(String message) {
        if (!message.equals(lastError)) warning.accept(message);
        lastError = message;
    }

    @Override
    public void close() {
        running.set(false);
        DatagramSocket current = socket;
        if (current != null) current.close();
        Thread currentThread = thread;
        if (currentThread != null) {
            try {
                currentThread.join(2_000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        socket = null;
        thread = null;
    }
}
