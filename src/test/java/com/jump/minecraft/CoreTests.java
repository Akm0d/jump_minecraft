package com.jump.minecraft;

import java.nio.charset.StandardCharsets;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class CoreTests {
    @Test
    void udpClientSubscribesAndReceives() {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(2_000);
            OrderedFrameStore store = new OrderedFrameStore();
            FlightEndpointConfig config = new FlightEndpointConfig(
                    "127.0.0.1", server.getLocalPort(), "127.0.0.1", 0, 100, 1.0, 50);
            try (JumpUdpClient client = new JumpUdpClient(config, store, ignored -> { }, ignored -> { })) {
                client.start();
                byte[] heartbeatBuffer = new byte[2048];
                DatagramPacket heartbeat = new DatagramPacket(heartbeatBuffer, heartbeatBuffer.length);
                server.receive(heartbeat);
                String heartbeatJson = new String(
                        heartbeat.getData(), 0, heartbeat.getLength(), StandardCharsets.UTF_8);
                assertTrue(heartbeatJson.contains("\"helicopterFlag\":false"), "UDP heartbeat contract");

                String frameJson = "{\"findex\":123,\"state\":\"flying\","
                        + "\"pos\":{\"x\":1,\"y\":2,\"z\":3},"
                        + "\"forward\":{\"x\":0,\"y\":0,\"z\":1},"
                        + "\"up\":{\"x\":0,\"y\":1,\"z\":0}}";
                byte[] payload = frameJson.getBytes(StandardCharsets.UTF_8);
                server.send(new DatagramPacket(payload, payload.length, heartbeat.getSocketAddress()));
                long deadline = System.nanoTime() + 2_000_000_000L;
                while (store.latest() == null && System.nanoTime() < deadline) Thread.sleep(5L);
                assertTrue(store.latest() != null, "UDP frame received");
                assertEquals(123L, store.latest().frame().findex(), "UDP frame decoded");
            }
        } catch (Exception error) {
            throw new AssertionError("UDP integration test failed", error);
        }
    }

    @Test
    void protocolDecodesV1AndBuildsHeartbeat() {
        String json = "{\"findex\":42,\"state\":\"pre_deployed\","
                + "\"pos\":{\"x\":1,\"y\":2,\"z\":3},"
                + "\"forward\":{\"x\":0,\"y\":0,\"z\":1},"
                + "\"up\":{\"x\":0,\"y\":1,\"z\":0},"
                + "\"hasTracking\":true,\"speed\":37.5,"
                + "\"ignored\":{\"nested\":[false,1,\"ok\"]}}";
        FlightFrame frame = JumpProtocol.decodeV1(json.getBytes(StandardCharsets.UTF_8), json.length());
        assertEquals(42L, frame.findex(), "findex");
        assertEquals(FlightState.PRE_DEPLOYED, frame.state(), "state");
        assertEquals(2.0, frame.position().y(), 1.0e-6, "position");
        assertEquals(37.5, frame.speed(), 1.0e-6, "speed");
        String heartbeat = new String(JumpProtocol.heartbeat(99L, 3.0), StandardCharsets.UTF_8);
        assertTrue(heartbeat.contains("\"findex\":99"), "heartbeat findex");
        assertTrue(heartbeat.contains("\"helicopterFlag\":false"), "required helicopter flag");
    }

    @Test
    void orderingRejectsDuplicateAndOldFrames() {
        OrderedFrameStore store = new OrderedFrameStore();
        assertTrue(store.offer(frame(10, FlightState.FLYING, new Vec3(0, 0, 0), new Vec3(0, 0, 1)), 100), "first accepted");
        assertFalse(store.offer(frame(10, FlightState.FLYING, new Vec3(1, 0, 0), new Vec3(0, 0, 1)), 110), "duplicate rejected");
        assertFalse(store.offer(frame(9, FlightState.FLYING, new Vec3(2, 0, 0), new Vec3(0, 0, 1)), 120), "old rejected");
        assertTrue(store.offer(frame(11, FlightState.FLYING, new Vec3(3, 0, 0), new Vec3(0, 0, 1)), 130), "new accepted");
        assertEquals(11L, store.latest().frame().findex(), "latest ordering");
    }

    @Test
    void staleDetectionUsesReceiveTime() {
        OrderedFrameStore store = new OrderedFrameStore();
        assertTrue(store.isStale(1_000, 50), "empty is stale");
        store.offer(frame(1, FlightState.PREPARING, new Vec3(0, 0, 0), new Vec3(0, 0, 1)), 1_000);
        assertFalse(store.isStale(1_050, 50), "boundary remains fresh");
        assertTrue(store.isStale(1_051, 50), "past boundary is stale");
    }

    @Test
    void coordinateMappingAnchorsAndRotates() {
        FlightFrame origin = frame(1, FlightState.FLYING, new Vec3(100, 50, -20), new Vec3(0, 0, 1));
        CoordinateMapper mapper = new CoordinateMapper(new Vec3(10, 70, 30), 90.0f, origin, 1.0, 0.0);
        MappedPose anchored = mapper.map(origin);
        assertEquals(10, anchored.x(), 1.0e-6, "anchor x");
        assertEquals(70, anchored.y(), 1.0e-6, "anchor y");
        assertEquals(30, anchored.z(), 1.0e-6, "anchor z");
        assertEquals(90, anchored.yaw(), 1.0e-6, "anchor yaw");

        FlightFrame moved = frame(2, FlightState.FLYING, new Vec3(100, 52, -10), new Vec3(-1, 0, 0));
        MappedPose pose = mapper.map(moved);
        assertEquals(0, pose.x(), 1.0e-6, "rotated forward displacement x");
        assertEquals(72, pose.y(), 1.0e-6, "vertical displacement");
        assertEquals(30, pose.z(), 1.0e-6, "rotated forward displacement z");
        assertEquals(180, Math.abs(pose.yaw()), 1.0e-6, "mapped yaw");
    }

    @Test
    void coordinateMappingConvertsPitch() {
        FlightFrame origin = frame(1, FlightState.FLYING, new Vec3(0, 0, 0), new Vec3(0, 0, 1));
        CoordinateMapper mapper = new CoordinateMapper(new Vec3(0, 0, 0), 0, origin, 1, 0);
        MappedPose upward = mapper.map(frame(2, FlightState.FLYING, new Vec3(0, 0, 0), new Vec3(0, 1, 1)));
        assertEquals(-45, upward.pitch(), 1.0e-6, "Minecraft pitch is positive downward");
    }

    @Test
    void lifecycleCoversLaunchLandingAndReset() {
        FlightLifecycle lifecycle = new FlightLifecycle();
        lifecycle.arm();
        assertEquals(SessionDecision.WAIT, lifecycle.observe(FlightState.PREPARING), "wait preparing");
        assertEquals(SessionDecision.CAPTURE_MINECRAFT_ORIGIN, lifecycle.observe(FlightState.JUMPING), "capture on jumping");
        assertEquals(SessionDecision.START_TRAJECTORY, lifecycle.observe(FlightState.FLYING), "start flying");
        assertEquals(SessionDecision.APPLY_TRAJECTORY, lifecycle.observe(FlightState.PRE_DEPLOYED), "follow predeploy");
        assertEquals(SessionDecision.APPLY_TRAJECTORY, lifecycle.observe(FlightState.DEPLOYED), "follow landing");
        assertEquals(SessionDecision.RESTORE_LANDED, lifecycle.observe(FlightState.LANDED), "restore landed");
        assertFalse(lifecycle.isArmed(), "landing disarms");

        lifecycle.arm();
        assertEquals(SessionDecision.START_TRAJECTORY, lifecycle.observe(FlightState.FLYING), "direct flying supported");
        assertEquals(SessionDecision.RESTORE_RESET, lifecycle.observe(FlightState.PREPARING), "preparing resets active session");

        lifecycle.arm();
        lifecycle.observe(FlightState.JUMPING);
        assertEquals(SessionDecision.START_TRAJECTORY, lifecycle.observe(FlightState.CANOPY_FLYING), "canopy trajectory followed");
    }

    private static FlightFrame frame(long findex, FlightState state, Vec3 position, Vec3 forward) {
        return new FlightFrame(findex, state, position, forward, new Vec3(0, 1, 0), true, 30);
    }

}
