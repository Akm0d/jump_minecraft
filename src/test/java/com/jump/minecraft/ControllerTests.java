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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ControllerTests {
    @Test
    void appliesMovementAndRetainsProtectedFlightModeOnLanding() {
        World world = proxy(World.class, (instance, method, args) -> switch (method.getName()) {
            case "isChunkLoaded" -> true;
            default -> defaultValue(instance, method, args);
        });
        PlayerState player = new PlayerState(new Location(world, 10, 64, 20, 90, 0));
        OrderedFrameStore frames = new OrderedFrameStore();
        FlightController controller = controller(player, frames, 1_000);
        controller.arm(player.proxy);

        offer(frames, 1, FlightState.JUMPING, new Vec3(100, 100, 100), new Vec3(0, 0, 1));
        controller.tick();
        assertTrue(!player.gravity && player.allowFlight && player.flying,
                "Minecraft physics disabled at jumping");

        offer(frames, 2, FlightState.FLYING, new Vec3(100, 100, 100), new Vec3(0, 0, 1));
        controller.tick();
        assertEquals(10, player.location.getX(), 1.0e-6, "flying anchor x");
        assertEquals(20, player.location.getZ(), 1.0e-6, "flying anchor z");
        assertEquals(90, player.location.getYaw(), 1.0e-6, "flying anchor yaw");

        offer(frames, 3, FlightState.PRE_DEPLOYED, new Vec3(100, 99, 101), new Vec3(0, 0, 1));
        controller.tick();
        assertEquals(9, player.location.getX(), 1.0e-6, "relative forward maps to heading-aligned x");
        assertEquals(63, player.location.getY(), 1.0e-6, "relative altitude");

        offer(frames, 4, FlightState.DEPLOYED, new Vec3(100, 98, 102), new Vec3(0, 0, 1));
        controller.tick();
        offer(frames, 5, FlightState.LANDED, new Vec3(100, 97, 103), new Vec3(0, 0, 1));
        controller.tick();
        assertEquals(7, player.location.getX(), 1.0e-6, "final landed location applied");
        assertTrue(!player.gravity && player.allowFlight && player.flying,
                "protected FLIGHT controls remain engaged at landed");
        assertTrue(controller.isDesignated(player.id), "landed preserves station assignment");
        assertTrue(controller.status().contains("protected FLIGHT mode retained"), "station returns to protected ready state");

        offer(frames, 6, FlightState.JUMPING, new Vec3(200, 200, 200), new Vec3(0, 0, 1));
        controller.tick();
        offer(frames, 7, FlightState.FLYING, new Vec3(200, 200, 200), new Vec3(0, 0, 1));
        controller.tick();
        assertTrue(controller.isActive(), "repeat flight starts without reassignment");
    }

    @Test
    void holdsProtectedPresentationOnResetAndStaleFrames() {
        World world = proxy(World.class, (instance, method, args) -> switch (method.getName()) {
            case "isChunkLoaded" -> true;
            default -> defaultValue(instance, method, args);
        });
        PlayerState player = new PlayerState(new Location(world, 0, 70, 0, 0, 0));
        OrderedFrameStore resetFrames = new OrderedFrameStore();
        FlightController resetController = controller(player, resetFrames, 1_000);
        resetController.arm(player.proxy);
        offer(resetFrames, 1, FlightState.JUMPING, new Vec3(0, 0, 0), new Vec3(0, 0, 1));
        resetController.tick();
        offer(resetFrames, 2, FlightState.PREPARING, new Vec3(0, 0, 0), new Vec3(0, 0, 1));
        resetController.tick();
        assertFalse(player.gravity, "reset retains protected FLIGHT mode");

        OrderedFrameStore staleFrames = new OrderedFrameStore();
        FlightController staleController = controller(player, staleFrames, 1);
        staleController.arm(player.proxy);
        offer(staleFrames, 3, FlightState.JUMPING, new Vec3(0, 0, 0), new Vec3(0, 0, 1));
        staleController.tick();
        FlightFrame stale = frame(4, FlightState.FLYING, new Vec3(0, 0, 0), new Vec3(0, 0, 1));
        staleFrames.offer(stale, System.nanoTime() - 10_000_000L);
        staleController.tick();
        assertFalse(player.gravity, "stale input holds protected position without gravity");
        assertTrue(staleController.isDesignated(player.id), "stale input preserves station assignment");

        OrderedFrameStore resetStreamFrames = new OrderedFrameStore();
        FlightController resetStreamController = controller(player, resetStreamFrames, 1_000);
        resetStreamController.arm(player.proxy);
        offer(resetStreamFrames, 5, FlightState.JUMPING, new Vec3(0, 0, 0), new Vec3(0, 0, 1));
        resetStreamController.tick();
        assertFalse(player.gravity, "launch engages Minecraft control before stream reset");
        resetStreamFrames.clear();
        resetStreamController.tick();
        assertFalse(player.gravity, "cleared stream retains protected presentation");
        assertTrue(resetStreamController.isDesignated(player.id),
                "stream reset preserves the station assignment for reconnect");
    }

    private static FlightController controller(PlayerState player, OrderedFrameStore frames, long staleMillis) {
        Server server = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
            case "getPlayer" -> player.id.equals(args[0]) ? player.proxy : null;
            case "getPlayerExact" -> player.proxy.getName().equals(args[0]) ? player.proxy : null;
            case "getOnlinePlayers" -> List.of(player.proxy);
            default -> defaultValue(instance, method, args);
        });
        return new FlightController(
                server, frames, new MovementConfig(staleMillis, 1.0, 0.0, 20.0), ignored -> { });
    }

    private static void offer(
            OrderedFrameStore store, long index, FlightState state, Vec3 position, Vec3 forward) {
        store.offer(frame(index, state, position, forward), System.nanoTime());
    }

    private static FlightFrame frame(long index, FlightState state, Vec3 position, Vec3 forward) {
        return new FlightFrame(index, state, position, forward, new Vec3(0, 1, 0), true, 30);
    }

    private static final class PlayerState implements InvocationHandler {
        private final UUID id = UUID.randomUUID();
        private final Player proxy = proxy(Player.class, this);
        private Location location;
        private boolean gravity = true;
        private boolean allowFlight;
        private boolean flying;
        private float fallDistance;
        private Vector velocity = new Vector();

        private PlayerState(Location location) {
            this.location = location;
        }

        @Override
        public Object invoke(Object instance, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> "test-player";
                case "isOnline" -> true;
                case "getLocation" -> location;
                case "teleport" -> {
                    location = (Location) args[0];
                    yield true;
                }
                case "hasGravity" -> gravity;
                case "setGravity" -> {
                    gravity = (boolean) args[0];
                    yield null;
                }
                case "getAllowFlight" -> allowFlight;
                case "setAllowFlight" -> {
                    allowFlight = (boolean) args[0];
                    yield null;
                }
                case "isFlying" -> flying;
                case "setFlying" -> {
                    flying = (boolean) args[0];
                    yield null;
                }
                case "getFallDistance" -> fallDistance;
                case "setFallDistance" -> {
                    fallDistance = (float) args[0];
                    yield null;
                }
                case "getVelocity" -> velocity;
                case "setVelocity" -> {
                    velocity = (Vector) args[0];
                    yield null;
                }
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
