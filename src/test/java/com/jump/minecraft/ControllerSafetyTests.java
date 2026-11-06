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
    void restoresOnDisconnectAndUnsafeMovementPaths() {
        Fixture disconnected = activeFlight();
        disconnected.player.online = false;
        disconnected.controller.tick();
        assertRestored(disconnected, false, "disconnect");

        Fixture changedWorld = activeFlight();
        changedWorld.player.location = new Location(world(), 0, 70, 0);
        continueFlight(changedWorld);
        assertRestored(changedWorld, true, "world change");

        Fixture unloadedChunk = activeFlight();
        unloadedChunk.world.chunkLoaded = false;
        continueFlight(unloadedChunk);
        assertRestored(unloadedChunk, true, "unloaded chunk");

        Fixture rejectedTeleport = activeFlight();
        rejectedTeleport.player.teleportAccepted = false;
        continueFlight(rejectedTeleport);
        assertRestored(rejectedTeleport, true, "rejected teleport");
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
