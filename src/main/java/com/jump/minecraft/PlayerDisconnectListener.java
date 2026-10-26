package com.jump.minecraft;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class PlayerDisconnectListener implements Listener {
    private final FlightController controller;

    PlayerDisconnectListener(FlightController controller) {
        this.controller = controller;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (controller.isDesignated(event.getPlayer().getUniqueId())) {
            controller.disarm("player disconnected");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        if (controller.isDesignated(event.getPlayer().getUniqueId())) {
            controller.disarm("player was kicked");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (controller.shouldBlockMovement(event.getPlayer().getUniqueId())) {
            event.setTo(event.getFrom());
        }
    }
}
