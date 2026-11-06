package com.jump.minecraft;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

final class JumpControlChannel implements PluginMessageListener {
    private final StationManager stations;
    private final StationRegistryServer registry;

    JumpControlChannel(StationManager stations, StationRegistryServer registry) {
        this.stations = stations;
        this.registry = registry;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
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
            return;
        }

        JumpRequestResult result = stations.requestJump(player.getUniqueId(), System.nanoTime());
        if (!result.accepted()) {
            player.sendMessage("JUMP request rejected: " + result.message());
            return;
        }
        if (!registry.sendControl(result.request())) {
            player.sendMessage("JUMP request failed: station bridge is not connected");
            return;
        }
        player.sendMessage("JUMP desktop simulation jump requested.");
    }
}
