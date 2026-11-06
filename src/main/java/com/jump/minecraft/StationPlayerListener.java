package com.jump.minecraft;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class StationPlayerListener implements Listener {
    private final StationManager stations;

    StationPlayerListener(StationManager stations) {
        this.stations = stations;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().sendMessage("JUMP assignment: " + stations.assignAutomatically(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stations.unassign(event.getPlayer().getUniqueId(), "player disconnected");
    }

    @EventHandler(ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        stations.unassign(event.getPlayer().getUniqueId(), "player was kicked");
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (stations.shouldBlockMovement(event.getPlayer().getUniqueId())) {
            event.setTo(event.getFrom());
        }
    }
}
