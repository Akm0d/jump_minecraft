package com.jump.minecraft;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class JumpUdpClient implements AutoCloseable {
    private static final int MAX_DATAGRAM = 65_507;

    private final FlightEndpointConfig config;
    private final OrderedFrameStore frames;
    private final Consumer<String> info;
    private final Consumer<String> warning;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong heartbeatSequence = new AtomicLong();
    private volatile DatagramSocket socket;
    private volatile Thread thread;
    private volatile String lastError = "not started";
    private volatile long acceptedFrames;
    private volatile long rejectedFrames;

    public JumpUdpClient(
            FlightEndpointConfig config,
            OrderedFrameStore frames,
            Consumer<String> info,
            Consumer<String> warning) {
        this.config = config;
        this.frames = frames;
        this.info = info;
        this.warning = warning;
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("UDP client already running");
        InetAddress remoteAddress = InetAddress.getByName(config.host());
        InetAddress bindAddress = InetAddress.getByName(config.localBindHost());
        DatagramSocket opened = new DatagramSocket(new InetSocketAddress(bindAddress, config.localPort()));
        opened.setSoTimeout(config.socketTimeoutMillis());
        socket = opened;
        InetSocketAddress remote = new InetSocketAddress(remoteAddress, config.port());
        thread = Thread.ofPlatform()
                .name("jump-udp-v1")
                .daemon(true)
                .uncaughtExceptionHandler((ignored, error) -> recordError("UDP thread terminated: " + error))
                .start(() -> run(opened, remote));
        lastError = "none";
        info.accept("JUMP UDP v1 subscriber bound to " + opened.getLocalSocketAddress()
                + " and targeting " + remote);
    }

    private void run(DatagramSocket activeSocket, InetSocketAddress remote) {
        byte[] receiveBuffer = new byte[MAX_DATAGRAM];
        long nextHeartbeat = 0L;
        while (running.get()) {
            long now = System.nanoTime();
            if (now >= nextHeartbeat) {
                try {
                    sendHeartbeat(activeSocket, remote);
                } catch (IOException error) {
                    recordError("heartbeat failed: " + error.getMessage());
                }
                nextHeartbeat = now + config.heartbeatIntervalMillis() * 1_000_000L;
            }
            DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            try {
                activeSocket.receive(packet);
                acceptPacket(packet, remote);
            } catch (SocketTimeoutException ignored) {
                // Timeout keeps heartbeat and shutdown checks responsive.
            } catch (IOException error) {
                if (running.get()) recordError("receive failed: " + error.getMessage());
            }
        }
    }

    private void acceptPacket(DatagramPacket packet, InetSocketAddress remote) {
        if (!packet.getAddress().equals(remote.getAddress()) || packet.getPort() != remote.getPort()) {
            rejectedFrames++;
            return;
        }
        try {
            FlightFrame frame = JumpProtocol.decodeV1(packet.getData(), packet.getLength());
            if (frames.offer(frame, System.nanoTime())) acceptedFrames++;
            else rejectedFrames++;
        } catch (IllegalArgumentException error) {
            rejectedFrames++;
            recordError("rejected v1 frame: " + error.getMessage());
        }
    }

    private void sendHeartbeat(DatagramSocket activeSocket, InetSocketAddress remote) throws IOException {
        long baseMicros = System.currentTimeMillis() * 1_000L;
        long sequence = heartbeatSequence.updateAndGet(previous -> Math.max(previous + 1L, baseMicros));
        byte[] payload = JumpProtocol.heartbeat(sequence, config.subscriptionExpirySeconds());
        activeSocket.send(new DatagramPacket(payload, payload.length, remote));
    }

    private void recordError(String message) {
        if (!message.equals(lastError)) warning.accept(message);
        lastError = message;
    }

    public String status() {
        DatagramSocket current = socket;
        String local = current == null ? "closed" : current.getLocalSocketAddress().toString();
        return "local=" + local + ", accepted=" + acceptedFrames + ", rejected=" + rejectedFrames
                + ", lastError=" + lastError;
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
