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
    private JumpControlChannel controls;
    private BukkitTask tickTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        try {
            MovementConfig movement = loadMovementConfig();
            StationNetworkConfig network = loadNetworkConfig();
            ChunkPreloader chunks = new PaperChunkPreloader(
                    this,
                    movement.maxChunksPerStation(),
                    movement.maxConcurrentChunkLoads(),
                    movement.maxRetainedChunks());
            stations = new StationManager(
                    getServer(),
                    movement,
                    network.maxStations(),
                    network.registrationTtlMillis(),
                    chunks,
                    message -> getLogger().info(message));
            registry = new StationRegistryServer(
                    network,
                    stations,
                    message -> getLogger().info(message),
                    message -> getLogger().warning(message));
            stations.setControlSender(request -> registry.sendControl(request));

            PluginCommand command = Objects.requireNonNull(getCommand("jump"), "Missing jump command");
            JumpCommand executor = new JumpCommand(getServer(), stations, registry, this);
            command.setExecutor(executor);
            command.setTabCompleter(new JumpTabCompleter(getServer(), stations));
            getServer().getPluginManager().registerEvents(new StationPlayerListener(stations), this);
            controls = new JumpControlChannel(stations, this);
            getServer().getMessenger().registerIncomingPluginChannel(
                    this, JumpControlProtocol.CHANNEL, controls);
            getServer().getMessenger().registerIncomingPluginChannel(
                    this, JumpDesktopInput.CHANNEL, controls);
            getServer().getMessenger().registerOutgoingPluginChannel(this, JumpModeProtocol.CHANNEL);
            tickTask = getServer().getScheduler().runTaskTimer(this, stations::tick, 1L, 1L);
            registry.start();
            getLogger().info(
                    "JUMP Minecraft multi-station v1/v2 telemetry and three-mode controls enabled; "
                            + "collision feedback "
                            + (movement.collisionFeedbackEnabled() ? "configured" : "disabled") + ".");
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
                positiveDouble("movement.max-step-blocks"),
                getConfig().getInt("movement.preload-lookahead-steps", 6),
                getConfig().getInt("movement.max-chunks-per-station", 12),
                getConfig().getInt("movement.max-concurrent-chunk-loads", 8),
                getConfig().getInt("movement.max-retained-chunks", 48),
                getConfig().getBoolean("collision.observation-enabled", true),
                positiveDouble("collision.max-distance-blocks"),
                getConfig().getBoolean("collision.feedback-enabled", false),
                positiveDouble("collision.response-acceleration"),
                positiveLong("collision.feedback-interval-millis"));
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
        getServer().getMessenger().unregisterIncomingPluginChannel(this, JumpControlProtocol.CHANNEL);
        getServer().getMessenger().unregisterIncomingPluginChannel(this, JumpDesktopInput.CHANNEL);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, JumpModeProtocol.CHANNEL);
        controls = null;
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
