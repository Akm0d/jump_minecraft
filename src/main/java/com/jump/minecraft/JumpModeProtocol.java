package com.jump.minecraft;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class JumpModeProtocol {
    static final String CHANNEL = "jump:mode";
    private static final byte VERSION = 1;

    private JumpModeProtocol() {
    }

    static void send(Plugin plugin, Player player, PlayerControlMode mode) {
        player.sendPluginMessage(plugin, CHANNEL, new byte[] {VERSION, mode.wireId()});
    }
}
