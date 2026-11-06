package com.jump.minecraft;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ControllerSafetyTests {
    @Test
    void freeModeKeepsProtectionAndFlightModeRebasesWithoutTeleporting() {
        Fixture fixture = activeFlight();
        assertEquals("mode=FREE", fixture.controller.toggleMode(fixture.player.id));
        assertTrue(fixture.player.gravity && !fixture.player.noPhysics,
                "FREE restores ordinary local movement physics");
        assertTrue(fixture.player.invulnerable, "FREE remains protected");
        assertTrue(!fixture.controller.shouldBlockMovement(fixture.player.id));

        offer(fixture.frames, 3, FlightState.PRE_DEPLOYED, new Vec3(0, 0, 100));
        fixture.controller.tick();
        assertEquals(0.0, fixture.player.location.getZ(), 1.0e-6,
                "telemetry is observed but does not move a FREE player");
        fixture.player.location = new Location(fixture.world.proxy, 0, 70, 5);

        assertEquals("mode=FLIGHT", fixture.controller.toggleMode(fixture.player.id));
        fixture.controller.tick();
        assertEquals(5.0, fixture.player.location.getZ(), 1.0e-6,
                "returning to FLIGHT anchors at the current safe position");
        offer(fixture.frames, 4, FlightState.PRE_DEPLOYED, new Vec3(0, 0, 101));
        fixture.controller.tick();
        assertEquals(6.0, fixture.player.location.getZ(), 1.0e-6);
    }

    @Test
    void distantFrameUsesBoundedCatchupWithoutReleasingControl() {
        Fixture fixture = activeFlight();
        offer(fixture.frames, 3, FlightState.PRE_DEPLOYED, new Vec3(0, 0, 100));
        fixture.controller.tick();
        assertEquals(20.0, fixture.player.location.getZ(), 1.0e-6);
        for (int count = 0; count < 4; count++) fixture.controller.tick();
        assertEquals(100.0, fixture.player.location.getZ(), 1.0e-6);
        assertProtected(fixture, "bounded catch-up");
    }

    @Test
    void restoresOnlyOnDisconnectAndHoldsProtectionOnRenderingFailures() {
        Fixture disconnected = activeFlight();
        disconnected.player.online = false;
        disconnected.controller.tick();
        assertRestored(disconnected, false, "disconnect");

        Fixture changedWorld = activeFlight();
        changedWorld.player.location = new Location(world(), 0, 70, 0);
        continueFlight(changedWorld);
        assertProtected(changedWorld, "world change");

        Fixture unloadedChunk = activeFlight();
        unloadedChunk.world.chunkLoaded = false;
        continueFlight(unloadedChunk);
        assertProtected(unloadedChunk, "unloaded chunk");
        unloadedChunk.world.chunkLoaded = true;
        unloadedChunk.controller.tick();
        assertEquals(1.0, unloadedChunk.player.location.getZ(), 1.0e-6,
                "pending frame resumes automatically when its chunk becomes ready");

        Fixture rejectedTeleport = activeFlight();
        rejectedTeleport.player.teleportAccepted = false;
        continueFlight(rejectedTeleport);
        assertProtected(rejectedTeleport, "rejected teleport");
    }

    private static Fixture activeFlight() {
        WorldState world = new WorldState();
        PlayerState player = new PlayerState(new Location(world.proxy, 0, 70, 0));
        OrderedFrameStore frames = new OrderedFrameStore();
        Server server = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "getPlayer" -> player.proxy;
            case "getOnlinePlayers" -> List.of(player.proxy);
            default -> defaultValue(instance, method, args);
        });
        FlightController controller = new FlightController(
                server, frames, new MovementConfig(1_000, 1.0, 0.0, 20.0), ignored -> { });
        controller.arm(player.proxy);
        offer(frames, 1, FlightState.JUMPING, new Vec3(0, 0, 0));
        controller.tick();
        offer(frames, 2, FlightState.FLYING, new Vec3(0, 0, 0));
        controller.tick();
        return new Fixture(world, player, frames, controller);
    }

    private static void continueFlight(Fixture fixture) {
        offer(fixture.frames, 3, FlightState.PRE_DEPLOYED, new Vec3(0, 0, 1));
        fixture.controller.tick();
    }

    private static void assertRestored(Fixture fixture, boolean assigned, String path) {
        assertTrue(fixture.player.gravity && !fixture.player.allowFlight && !fixture.player.flying,
                path + " restores normal controls");
        assertEquals(assigned, fixture.controller.isDesignated(fixture.player.id),
                path + " assignment policy");
    }

    private static void assertProtected(Fixture fixture, String path) {
        assertTrue(!fixture.player.gravity && fixture.player.allowFlight && fixture.player.flying,
                path + " holds protected FLIGHT controls");
        assertTrue(fixture.player.noPhysics && fixture.player.invulnerable && !fixture.player.collidable,
                path + " retains no-collision and damage protection");
        assertTrue(fixture.controller.isDesignated(fixture.player.id), path + " preserves assignment");
    }

    private static void offer(OrderedFrameStore frames, long index, FlightState state, Vec3 position) {
        frames.offer(new FlightFrame(
                        index, state, position, new Vec3(0, 0, 1), new Vec3(0, 1, 0), true, 30),
                System.nanoTime());
    }

    private static World world() {
        return proxy(World.class, ControllerSafetyTests::defaultValue);
    }

    private static final class WorldState implements InvocationHandler {
        private final World proxy = proxy(World.class, this);
        private boolean chunkLoaded = true;

        @Override
        public Object invoke(Object instance, Method method, Object[] args) {
            return method.getName().equals("isChunkLoaded")
                    ? chunkLoaded
                    : defaultValue(instance, method, args);
        }
    }

    private static final class PlayerState implements InvocationHandler {
        private final UUID id = UUID.randomUUID();
        private final Player proxy = proxy(Player.class, this);
        private Location location;
        private boolean gravity = true;
        private boolean allowFlight;
        private boolean flying;
        private boolean noPhysics;
        private boolean invulnerable;
        private boolean collidable = true;
        private boolean online = true;
        private boolean teleportAccepted = true;

        private PlayerState(Location location) {
            this.location = location;
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "safety-player";
                case "isOnline" -> online;
                case "getLocation" -> location;
                case "teleport" -> {
                    if (teleportAccepted) location = (Location) args[0];
                    yield teleportAccepted;
                }
                case "hasGravity" -> gravity;
                case "setGravity" -> { gravity = (boolean) args[0]; yield null; }
                case "getAllowFlight" -> allowFlight;
                case "setAllowFlight" -> { allowFlight = (boolean) args[0]; yield null; }
                case "isFlying" -> flying;
                case "setFlying" -> { flying = (boolean) args[0]; yield null; }
                case "hasNoPhysics" -> noPhysics;
                case "setNoPhysics" -> { noPhysics = (boolean) args[0]; yield null; }
                case "isInvulnerable" -> invulnerable;
                case "setInvulnerable" -> { invulnerable = (boolean) args[0]; yield null; }
                case "isCollidable" -> collidable;
                case "setCollidable" -> { collidable = (boolean) args[0]; yield null; }
                case "getFallDistance" -> 0.0f;
                case "setFallDistance", "sendMessage" -> null;
                case "getVelocity" -> new Vector();
                case "setVelocity" -> null;
                default -> defaultValue(instance, method, args);
            };
        }
    }

    private record Fixture(
            WorldState world,
            PlayerState player,
            OrderedFrameStore frames,
            FlightController controller) {
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
