package com.jump.minecraft;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

final class JumpControlChannel implements PluginMessageListener {
    private final StationManager stations;
    private final Plugin plugin;

    JumpControlChannel(StationManager stations, Plugin plugin) {
        this.stations = stations;
        this.plugin = plugin;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (JumpDesktopInput.CHANNEL.equals(channel)) {
            try {
                stations.forwardDesktopInput(player.getUniqueId(), JumpDesktopInput.decode(message));
            } catch (IllegalArgumentException ignored) {
                // Invalid or out-of-mode input is dropped without chat/log flooding.
            }
            return;
        }
        if (!JumpControlProtocol.CHANNEL.equals(channel)) return;
        final byte action;
        try {
            action = JumpControlProtocol.decode(message);
        } catch (IllegalArgumentException error) {
            player.sendMessage("JUMP control rejected: " + error.getMessage());
            return;
        }
        if (action == JumpControlProtocol.TOGGLE_MODE) {
            player.sendMessage("JUMP " + stations.toggleMode(player.getUniqueId()));
            PlayerControlMode mode = stations.mode(player.getUniqueId());
            if (mode != null) JumpModeProtocol.send(plugin, player, mode);
            return;
        }
    }
}
