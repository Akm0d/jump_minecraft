package com.jump.minecraft;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StationPlayerListenerTests {
    @Test
    void flightModesBlockPositionAndPreserveMouseLook() throws Exception {
        for (PlayerControlMode mode : List.of(PlayerControlMode.FLIGHT, PlayerControlMode.FLIGHT_DESKTOP)) {
            Fixture fixture = fixture(mode);
            Location from = new Location(fixture.world, 10, 64, 20, 5, 6);
            Location rotation = new Location(fixture.world, 10, 64, 20, 75, -20);
            PlayerMoveEvent rotationEvent = new PlayerMoveEvent(fixture.player.proxy, from, rotation);

            fixture.listener.onMove(rotationEvent);

            assertSame(rotation, rotationEvent.getTo(), mode + " leaves rotation-only events untouched");
            assertEquals(75, rotationEvent.getTo().getYaw(), 0.0, mode + " preserves yaw");
            assertEquals(-20, rotationEvent.getTo().getPitch(), 0.0, mode + " preserves pitch");

            Location movement = new Location(fixture.world, 11, 65, 22, 80, -25);
            PlayerMoveEvent movementEvent = new PlayerMoveEvent(fixture.player.proxy, from, movement);

            fixture.listener.onMove(movementEvent);

            Location constrained = movementEvent.getTo();
            assertEquals(10, constrained.getX(), 0.0, mode + " blocks X");
            assertEquals(64, constrained.getY(), 0.0, mode + " blocks Y");
            assertEquals(20, constrained.getZ(), 0.0, mode + " blocks Z");
            assertEquals(80, constrained.getYaw(), 0.0, mode + " preserves yaw while blocking position");
            assertEquals(-25, constrained.getPitch(), 0.0, mode + " preserves pitch while blocking position");
        }
    }

    @Test
    void minecraftModeLeavesMovementUntouched() throws Exception {
        Fixture fixture = fixture(PlayerControlMode.MINECRAFT);
        Location from = new Location(fixture.world, 10, 64, 20, 5, 6);
        Location requested = new Location(fixture.world, 11, 65, 22, 75, -20);
        PlayerMoveEvent event = new PlayerMoveEvent(fixture.player.proxy, from, requested);

        fixture.listener.onMove(event);

        assertSame(requested, event.getTo());
    }

    @Test
    void nullDestinationIsIgnoredSafely() throws Exception {
        Fixture fixture = fixture(PlayerControlMode.FLIGHT);
        PlayerMoveEvent event = new PlayerMoveEvent(
                fixture.player.proxy, new Location(fixture.world, 10, 64, 20), null);

        assertDoesNotThrow(() -> fixture.listener.onMove(event));
        assertNull(event.getTo());
    }

    @Test
    void stationaryTelemetrySkipsTeleportsAndMovementPreservesLocalLook() throws Exception {
        Fixture fixture = fixture(PlayerControlMode.FLIGHT);
        offer(fixture, 1, new Vec3(100, 100, 100), new Vec3(0, 0, 1));
        fixture.stations.tick();
        assertEquals(0, fixture.player.teleports, "initial stationary frame does not correct the camera");

        fixture.player.location.setYaw(73);
        fixture.player.location.setPitch(-24);
        offer(fixture, 2, new Vec3(100, 100, 100), new Vec3(1, 0, 0));
        fixture.stations.tick();
        assertEquals(0, fixture.player.teleports, "stationary orientation changes do not teleport");
        assertEquals(73, fixture.player.location.getYaw(), 0.0, "local yaw remains mouse-owned");
        assertEquals(-24, fixture.player.location.getPitch(), 0.0, "local pitch remains mouse-owned");
        assertTrue(fixture.stations.statusLines().getFirst().contains("teleport skipped"));

        offer(fixture, 3, new Vec3(100, 100, 100.0000005), new Vec3(1, 0, 0));
        fixture.stations.tick();
        assertEquals(0, fixture.player.teleports, "sub-tolerance floating-point noise is skipped");

        offer(fixture, 4, new Vec3(100, 100, 100.00001), new Vec3(1, 0, 0));
        fixture.stations.tick();
        assertEquals(1, fixture.player.teleports, "movement above the one-microunit tolerance is rendered");
        assertEquals(20.00001, fixture.player.location.getZ(), 1.0e-8, "small FLIGHT movement is retained");
        assertEquals(73, fixture.player.location.getYaw(), 0.0, "position teleport preserves local yaw");
        assertEquals(-24, fixture.player.location.getPitch(), 0.0, "position teleport preserves local pitch");
    }

    private static Fixture fixture(PlayerControlMode mode) throws Exception {
        World world = proxy(World.class, (instance, method, args) -> switch (method.getName()) {
            case "isChunkLoaded" -> true;
            default -> defaultValue(instance, method, args);
        });
        PlayerState player = new PlayerState(new Location(world, 10, 64, 20));
        Server server = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "getPlayer" -> player.id.equals(args[0]) ? player.proxy : null;
            case "getOnlinePlayers" -> List.of(player.proxy);
            default -> defaultValue(instance, method, args);
        });
        StationManager stations = new StationManager(
                server, new MovementConfig(1_000, 1.0, 0.0, 20.0), 1, 5_000, ignored -> { });
        InetSocketAddress endpoint = new InetSocketAddress(InetAddress.getByName("10.1.10.113"), 50_000);
        assertEquals(StationManager.RegistrationResult.REGISTERED,
                stations.register(new StationRegistration(
                        "test-station", "Test station", endpoint, System.nanoTime())));
        assertTrue(stations.assign("test-station", player.proxy).startsWith("assigned "));
        if (mode != PlayerControlMode.MINECRAFT) {
            assertEquals("mode=" + mode.displayName(), stations.setMode(player.id, mode));
        }
        return new Fixture(world, player, stations, new StationPlayerListener(stations));
    }

    private static void offer(Fixture fixture, long index, Vec3 position, Vec3 forward) {
        fixture.stations.session("test-station").frames().offer(new FlightFrame(
                        index, FlightState.PREPARING, position, forward, new Vec3(0, 1, 0), true, 30),
                System.nanoTime());
    }

    private record Fixture(
            World world, PlayerState player, StationManager stations, StationPlayerListener listener) {
    }

    private static final class PlayerState implements InvocationHandler {
        private final UUID id = UUID.randomUUID();
        private final Player proxy = proxy(Player.class, this);
        private Location location;
        private int teleports;

        private PlayerState(Location location) {
            this.location = location;
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "listener-player";
                case "isOnline" -> true;
                case "getLocation" -> location;
                case "teleport" -> {
                    location = (Location) args[0];
                    teleports++;
                    yield true;
                }
                case "hasGravity" -> true;
                case "isCollidable" -> true;
                case "getVelocity" -> new Vector();
                case "getMaximumAir", "getRemainingAir" -> 300;
                case "sendMessage" -> null;
                default -> defaultValue(instance, method, args);
            };
        }
    }

    private static Object defaultValue(Object instance, Method method, Object[] args) {
        return switch (method.getName()) {
            case "equals" -> instance == args[0];
            case "hashCode" -> System.identityHashCode(instance);
            case "toString" -> "test-" + method.getDeclaringClass().getSimpleName();
            default -> TestDefaults.value(method);
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
}
