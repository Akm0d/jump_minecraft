package com.jump.minecraft;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StationTests {
    @Test
    void sessionsKeepFramesOrderingAndHealthIndependent() throws Exception {
        StationManager manager = manager(4, 5_000);
        long now = System.nanoTime();
        InetSocketAddress bay1 = endpoint("10.1.10.111", 40_001);
        InetSocketAddress bay2 = endpoint("10.1.10.112", 40_002);
        assertEquals(StationManager.RegistrationResult.REGISTERED,
                manager.register(registration("bay-1", "Bay 1", bay1, now)));
        assertEquals(StationManager.RegistrationResult.REGISTERED,
                manager.register(registration("bay-2", "Bay 2", bay2, now)));

        byte[] bay1Frame = ProtocolV2Tests.frame(100, FlightState.FLYING, null);
        byte[] bay2Frame = ProtocolV2Tests.frame(7, FlightState.PREPARING, null);
        assertTrue(manager.acceptFrame(bay1, bay1Frame, bay1Frame.length, now + 1));
        assertTrue(manager.acceptFrame(bay2, bay2Frame, bay2Frame.length, now + 2));
        assertEquals(100, manager.session("bay-1").frames().latest().frame().findex());
        assertEquals(FlightState.FLYING, manager.session("bay-1").frames().latest().frame().state());
        assertEquals(7, manager.session("bay-2").frames().latest().frame().findex());
        assertEquals(FlightState.PREPARING, manager.session("bay-2").frames().latest().frame().state());

        byte[] oldBay1 = ProtocolV2Tests.frame(99, FlightState.LANDED, null);
        manager.acceptFrame(bay1, oldBay1, oldBay1.length, now + 3);
        assertEquals(100, manager.session("bay-1").frames().latest().frame().findex());
        assertEquals(7, manager.session("bay-2").frames().latest().frame().findex());
    }

    @Test
    void registrationHandlesRestartDhcpDuplicateIdsAndCapacity() throws Exception {
        StationManager manager = manager(2, 5_000);
        long now = System.nanoTime();
        InetSocketAddress first = endpoint("10.1.10.111", 41_001);
        assertEquals(StationManager.RegistrationResult.REGISTERED,
                manager.register(registration("bay-1", "Bay 1", first, now)));
        assertEquals(StationManager.RegistrationResult.REGISTERED,
                manager.register(registration(
                        "bay-2", "Bay 2", endpoint("10.1.10.112", 41_005), now + 1)));
        assertEquals(StationManager.RegistrationResult.CAPACITY,
                manager.register(registration(
                        "dev", "Dev", endpoint("10.1.10.113", 41_006), now + 2)));
        assertEquals(StationManager.RegistrationResult.DUPLICATE,
                manager.register(registration(
                        "bay-1", "Bay 1", endpoint("10.1.10.111", 41_002), now + 3)),
                "a second live process on the same host cannot steal the station identity");
        assertEquals(StationManager.RegistrationResult.DUPLICATE,
                manager.register(registration(
                        "bay-1", "Impostor", endpoint("10.1.10.200", 41_003), now + 4)),
                "active duplicate identity from another host is rejected");
        byte[] oldStream = ProtocolV2Tests.frame(100, FlightState.FLYING, null);
        assertTrue(manager.acceptFrame(first, oldStream, oldStream.length, now + 5));

        InetSocketAddress restarted = endpoint("10.1.10.111", 41_004);
        assertEquals(StationManager.RegistrationResult.REFRESHED,
                manager.register(registration(
                        "bay-1", "Bay 1", restarted, now + 6_000_000_000L)),
                "expired bridge can restart with a new source port");
        assertNull(manager.session("bay-1").frames().latest(),
                "new endpoint resets the previous stream ordering watermark");
        byte[] newStream = ProtocolV2Tests.frame(1, FlightState.PREPARING, null);
        assertTrue(manager.acceptFrame(
                restarted, newStream, newStream.length, now + 6_000_000_001L));
        assertEquals(1, manager.session("bay-1").frames().latest().frame().findex());
        assertNull(manager.session("bay-2"), "expired unassigned station was pruned");
        assertEquals(StationManager.RegistrationResult.REGISTERED,
                manager.register(registration(
                        "dev", "Dev", endpoint("10.1.10.113", 41_006), now + 6_000_000_002L)),
                "pruning releases station capacity");

        assertEquals(StationManager.RegistrationResult.REFRESHED,
                manager.register(registration(
                        "bay-1", "Bay 1", endpoint("10.1.10.120", 41_007), now + 12_000_000_000L)),
                "expired registration can return after DHCP address change");
    }

    @Test
    void playerAssignmentUsesAddressAndRejectsAmbiguity() throws Exception {
        StationManager manager = manager(4, 5_000);
        long now = System.nanoTime();
        manager.register(registration("bay-1", "Bay 1", endpoint("10.1.10.111", 42_001), now));
        manager.register(registration("bay-2", "Bay 2", endpoint("10.1.10.112", 42_002), now));
        Player player1 = player("bay1player", "10.1.10.111");
        Player player2 = player("bay2player", "10.1.10.112");

        assertTrue(manager.assignAutomatically(player1).startsWith("assigned bay1player to bay-1"));
        assertTrue(manager.assignAutomatically(player2).startsWith("assigned bay2player to bay-2"));
        assertTrue(manager.session("bay-1").isAssignedTo(player1.getUniqueId()));
        assertTrue(manager.session("bay-2").isAssignedTo(player2.getUniqueId()));

        StationManager ambiguous = manager(4, 5_000);
        ambiguous.register(registration("a", "A", endpoint("10.1.10.150", 43_001), now));
        ambiguous.register(registration("b", "B", endpoint("10.1.10.150", 43_002), now));
        assertTrue(ambiguous.assignAutomatically(player("shared", "10.1.10.150")).startsWith("ambiguous"));
        assertFalse(ambiguous.session("a").isAssigned());
        assertFalse(ambiguous.session("b").isAssigned());
    }

    @Test
    void tickRetriesLateRegistrationWithUnambiguousSingleStationFallback() throws Exception {
        Player remotePlayer = player("remote", "10.1.10.200");
        StationManager manager = managerWithPlayer(4, remotePlayer);

        manager.tick();
        registerBayOne(manager, 43_101);
        assertFalse(manager.session("bay-1").isAssigned(),
                "registration itself does not mutate Bukkit player state off-thread");

        manager.tick();

        assertBayOneAssigned(manager, remotePlayer);
    }

    @Test
    void expiredAssignedStationIsRetainedForSafeReconnect() throws Exception {
        Player player = player("bay1player", "10.1.10.111");
        StationManager manager = managerWithPlayer(1, player);
        long now = System.nanoTime();
        registerBayOne(manager, 43_201);
        assertTrue(manager.assignAutomatically(player).startsWith("assigned bay1player to bay-1"));

        assertEquals(StationManager.RegistrationResult.CAPACITY,
                manager.register(registration(
                        "bay-2", "Bay 2", endpoint("10.1.10.112", 43_202),
                        now + 6_000_000_000L)),
                "an assigned station is not evicted merely because registration expired");
        assertBayOneAssigned(manager, player);
    }

    @Test
    void concurrentStreamsDoNotCrossSessions() throws Exception {
        StationManager manager = manager(4, 5_000);
        long now = System.nanoTime();
        InetSocketAddress bay1 = endpoint("10.1.10.111", 44_001);
        InetSocketAddress bay2 = endpoint("10.1.10.112", 44_002);
        manager.register(registration("bay-1", "Bay 1", bay1, now));
        manager.register(registration("bay-2", "Bay 2", bay2, now));
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = new ArrayList<>();
        Thread first = sender(manager, bay1, FlightState.FLYING, start, errors);
        Thread second = sender(manager, bay2, FlightState.CANOPY_FLYING, start, errors);
        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();
        assertTrue(errors.isEmpty(), "stream threads completed cleanly: " + errors);
        assertEquals(999, manager.session("bay-1").frames().latest().frame().findex());
        assertEquals(FlightState.FLYING, manager.session("bay-1").frames().latest().frame().state());
        assertEquals(999, manager.session("bay-2").frames().latest().frame().findex());
        assertEquals(FlightState.CANOPY_FLYING, manager.session("bay-2").frames().latest().frame().state());
    }

    @Test
    void registrationParserAndPrivateAddressGuardAreStrict() throws Exception {
        String json = "{\"jumpStation\":1,\"stationId\":\"bay-1\",\"stationName\":\"Bay 1\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        StationRegistration parsed = StationRegistration.decodeOrNull(
                bytes, bytes.length, endpoint("10.1.10.111", 45_001), 123);
        assertEquals("bay-1", parsed.stationId());
        assertTrue(PrivateAddresses.isPrivate(InetAddress.getByName("127.0.0.1")));
        assertTrue(PrivateAddresses.isPrivate(InetAddress.getByName("10.1.10.111")));
        assertTrue(PrivateAddresses.isPrivate(InetAddress.getByName("100.100.20.30")));
        assertFalse(PrivateAddresses.isPrivate(InetAddress.getByName("8.8.8.8")));
    }

    private static Thread sender(
            StationManager manager,
            InetSocketAddress endpoint,
            FlightState state,
            CountDownLatch start,
            List<Throwable> errors) {
        return Thread.ofPlatform().unstarted(() -> {
            try {
                start.await();
                for (int index = 0; index < 1_000; index++) {
                    byte[] frame = ProtocolV2Tests.frame(index, state, null);
                    manager.acceptFrame(endpoint, frame, frame.length, System.nanoTime());
                }
            } catch (Throwable error) {
                synchronized (errors) {
                    errors.add(error);
                }
            }
        });
    }

    private static StationManager manager(int maxStations, long ttlMillis) {
        return manager(maxStations, ttlMillis, List.of());
    }

    private static StationManager managerWithPlayer(int maxStations, Player player) {
        return manager(maxStations, 5_000, List.of(player));
    }

    private static void registerBayOne(StationManager manager, int port) throws Exception {
        manager.register(registration(
                "bay-1", "Bay 1", endpoint("10.1.10.111", port), System.nanoTime()));
    }

    private static void assertBayOneAssigned(StationManager manager, Player player) {
        assertTrue(manager.session("bay-1").isAssignedTo(player.getUniqueId()),
                "bay-1 is assigned to the expected player");
    }

    private static StationManager manager(int maxStations, long ttlMillis, List<Player> onlinePlayers) {
        Server server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (proxy, method, args) -> method.getName().equals("getOnlinePlayers")
                        ? onlinePlayers
                        : TestDefaults.value(method));
        return new StationManager(
                server,
                new MovementConfig(1_000, 1.0, 0.0, 20.0),
                maxStations,
                ttlMillis,
                ignored -> { });
    }

    private static StationRegistration registration(
            String id,
            String name,
            InetSocketAddress endpoint,
            long receivedNanos) {
        return new StationRegistration(id, name, endpoint, receivedNanos);
    }

    private static InetSocketAddress endpoint(String address, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(address), port);
    }

    private static Player player(String name, String address) throws Exception {
        UUID id = UUID.randomUUID();
        InetSocketAddress endpoint = endpoint(address, 50_000);
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "getAddress" -> endpoint;
                    case "isOnline" -> true;
                    default -> TestDefaults.value(method);
                });
    }
}
