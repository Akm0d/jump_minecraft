package com.jump.minecraft;

import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class JumpMinecraftPlugin extends JavaPlugin {
    private OrderedFrameStore frames;
    private JumpUdpClient udpClient;
    private FlightController flightController;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            FlightEndpointConfig endpoint = loadEndpointConfig();
            frames = new OrderedFrameStore();
            udpClient = new JumpUdpClient(
                    endpoint,
                    frames,
                    message -> getLogger().info(message),
                    message -> getLogger().warning(message));
            flightController = new FlightController(
                    getServer(),
                    frames,
                    new MovementConfig(
                            positiveLong("flight.stale-after-millis"),
                            positiveDouble("movement.blocks-per-meter"),
                            getConfig().getDouble("movement.heading-offset-degrees", 0.0),
                            positiveDouble("movement.max-step-blocks")),
                    message -> getLogger().info(message));

            PluginCommand command = Objects.requireNonNull(getCommand("jump"), "Missing jump command");
            JumpCommand executor = new JumpCommand(getServer(), flightController, udpClient);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            getServer().getPluginManager().registerEvents(
                    new PlayerDisconnectListener(flightController), this);
            tickTask = getServer().getScheduler().runTaskTimer(this, flightController::tick, 1L, 1L);
            udpClient.start();

            if (endpoint.host().equals("127.0.0.1") || endpoint.host().equals("localhost")) {
                getLogger().warning("FLIGHT endpoint is loopback. A remote Windows FLIGHT host cannot reach this socket without an explicit relay/configuration change.");
            }
            getLogger().info("JUMP Minecraft bridge enabled in passive v1 mode; it sends heartbeats only.");
        } catch (RuntimeException | IOException error) {
            getLogger().log(Level.SEVERE, "Could not start JUMP bridge", error);
            closeResources();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        closeResources();
    }

    private FlightEndpointConfig loadEndpointConfig() {
        return new FlightEndpointConfig(
                getConfig().getString("flight.host", "127.0.0.1"),
                getConfig().getInt("flight.port", 1201),
                getConfig().getString("flight.local-bind-host", "0.0.0.0"),
                getConfig().getInt("flight.local-port", 0),
                positiveLong("flight.heartbeat-interval-millis"),
                positiveDouble("flight.subscription-expiry-seconds"),
                Math.toIntExact(positiveLong("flight.socket-timeout-millis")));
    }

    private long positiveLong(String path) {
        long value = getConfig().getLong(path);
        if (value <= 0L) throw new IllegalArgumentException(path + " must be positive");
        return value;
    }

    private double positiveDouble(String path) {
        double value = getConfig().getDouble(path);
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(path + " must be finite and positive");
        }
        return value;
    }

    private void closeResources() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (flightController != null) flightController.disarm("plugin shutdown");
        if (udpClient != null) {
            udpClient.close();
            udpClient = null;
        }
    }
}
