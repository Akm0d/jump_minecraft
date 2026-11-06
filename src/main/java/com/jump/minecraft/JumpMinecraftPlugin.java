package com.jump.minecraft;

import java.io.IOException;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class JumpMinecraftPlugin extends JavaPlugin {
    private StationManager stations;
    private StationRegistryServer registry;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        try {
            MovementConfig movement = loadMovementConfig();
            StationNetworkConfig network = loadNetworkConfig();
            stations = new StationManager(
                    getServer(),
                    movement,
                    network.maxStations(),
                    network.registrationTtlMillis(),
                    message -> getLogger().info(message));
            registry = new StationRegistryServer(
                    network,
                    stations,
                    message -> getLogger().info(message),
                    message -> getLogger().warning(message));

            PluginCommand command = Objects.requireNonNull(getCommand("jump"), "Missing jump command");
            JumpCommand executor = new JumpCommand(getServer(), stations, registry);
            command.setExecutor(executor);
            command.setTabCompleter(new JumpTabCompleter(getServer(), stations));
            getServer().getPluginManager().registerEvents(new StationPlayerListener(stations), this);
            tickTask = getServer().getScheduler().runTaskTimer(this, stations::tick, 1L, 1L);
            registry.start();
            getLogger().info(
                    "JUMP Minecraft multi-station bridge enabled in passive v1/v2 mode; "
                            + "it sends subscription heartbeats only.");
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

    private StationNetworkConfig loadNetworkConfig() {
        return new StationNetworkConfig(
                getConfig().getString("registration.bind-host", "0.0.0.0"),
                getConfig().getInt("registration.port", 12101),
                positiveLong("registration.heartbeat-interval-millis"),
                positiveDouble("registration.subscription-expiry-seconds"),
                Math.toIntExact(positiveLong("registration.socket-timeout-millis")),
                positiveLong("registration.station-ttl-millis"),
                Math.toIntExact(positiveLong("registration.max-stations")));
    }

    private MovementConfig loadMovementConfig() {
        return new MovementConfig(
                positiveLong("flight.stale-after-millis"),
                positiveDouble("movement.blocks-per-meter"),
                getConfig().getDouble("movement.heading-offset-degrees", 0.0),
                positiveDouble("movement.max-step-blocks"));
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
        if (registry != null) {
            registry.close();
            registry = null;
        }
        if (stations != null) {
            stations.close();
            stations = null;
        }
    }
}
