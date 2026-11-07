package com.jump.minecraft;

import java.lang.reflect.Proxy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StationRegistryTests {
    @Test
    void registersHeartbeatsReceivesFramesAndHandlesBridgeRestart() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        int port;
        try (DatagramSocket probe = new DatagramSocket(0, loopback)) {
            port = probe.getLocalPort();
        }
        StationManager manager = manager();
        StationNetworkConfig config = new StationNetworkConfig(
                loopback.getHostAddress(), port, 50, 1.0, 25, 1_000, 4);
        InetSocketAddress registryEndpoint = new InetSocketAddress(loopback, port);
        try (StationRegistryServer registry = new StationRegistryServer(
                        config, manager, ignored -> { }, ignored -> { });
                DatagramSocket firstBridge = new DatagramSocket(0, loopback)) {
            registry.start();
            firstBridge.setSoTimeout(2_000);
            sendRegistration(firstBridge, registryEndpoint, "dev");
            assertTrue(receiveJson(firstBridge).contains("\"jumpStationAck\":1"), "registration ack");
            assertTrue(receiveJson(firstBridge).contains("\"helicopterFlag\":false"), "FLIGHT heartbeat");
            assertTrue(registry.sendControl(StationControlRequest.desktopInput(
                    "dev", new JumpDesktopInput(1, 0, 2, 10, true))));
            String desktopControl = receiveJsonContaining(firstBridge, "\"action\":\"desktopInput\"");
            assertTrue(desktopControl.contains("\"action\":\"desktopInput\""));
            assertTrue(desktopControl.contains("\"jump\":true"));

            byte[] firstFrame = ("{\"findex\":10,\"state\":\"flying\","
                    + "\"pos\":{\"x\":1,\"y\":2,\"z\":3},"
                    + "\"forward\":{\"x\":0,\"y\":0,\"z\":1},"
                    + "\"up\":{\"x\":0,\"y\":1,\"z\":0}}")
                    .getBytes(StandardCharsets.UTF_8);
            firstBridge.send(new DatagramPacket(firstFrame, firstFrame.length, registryEndpoint));
            awaitFrame(manager, "dev", 10);

            try (DatagramSocket restartedBridge = new DatagramSocket(0, loopback)) {
                restartedBridge.setSoTimeout(2_000);
                sendRegistration(restartedBridge, registryEndpoint, "dev");
                assertTrue(receiveJson(restartedBridge).contains("\"status\":\"duplicate\""),
                        "a live endpoint cannot be stolen");
                Thread.sleep(1_050);
                sendRegistration(restartedBridge, registryEndpoint, "dev");
                assertTrue(receiveJson(restartedBridge).contains("\"status\":\"refreshed\""),
                        "restart is accepted after the old endpoint expires");
                byte[] restartedFrame = ProtocolV2Tests.frame(11, FlightState.CANOPY_FLYING, null);
                restartedBridge.send(new DatagramPacket(restartedFrame, restartedFrame.length, registryEndpoint));
                awaitFrame(manager, "dev", 11);
            }
            assertEquals(FlightState.CANOPY_FLYING,
                    manager.session("dev").frames().latest().frame().state());
            assertTrue(registry.status().contains("registrations=2"));
        }
    }

    private static void sendRegistration(
            DatagramSocket socket,
            InetSocketAddress registry,
            String stationId) throws Exception {
        String json = "{\"jumpStation\":1,\"stationId\":\"" + stationId
                + "\",\"stationName\":\"Development\"}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(payload, payload.length, registry));
    }

    private static String receiveJson(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[2_048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        return new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
    }

    private static String receiveJsonContaining(DatagramSocket socket, String expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            String json = receiveJson(socket);
            if (json.contains(expected)) return json;
        }
        throw new AssertionError("did not receive datagram containing " + expected);
    }

    private static void awaitFrame(StationManager manager, String stationId, long findex)
            throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while ((manager.session(stationId) == null
                || manager.session(stationId).frames().latest() == null
                || manager.session(stationId).frames().latest().frame().findex() != findex)
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(findex, manager.session(stationId).frames().latest().frame().findex());
    }

    private static StationManager manager() {
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (proxy, method, args) -> TestDefaults.value(method));
        return new StationManager(
                server,
                new MovementConfig(1_000, 1.0, 0.0, 20.0),
                4,
                1_000,
                ignored -> { });
    }

}
