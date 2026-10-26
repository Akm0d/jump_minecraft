package com.jump.minecraft;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class PlayerControlSnapshot {
    private final boolean gravity;
    private final boolean allowFlight;
    private final boolean flying;
    private final float fallDistance;
    private final Vector velocity;

    private PlayerControlSnapshot(Player player) {
        gravity = player.hasGravity();
        allowFlight = player.getAllowFlight();
        flying = player.isFlying();
        fallDistance = player.getFallDistance();
        velocity = player.getVelocity().clone();
    }

    static PlayerControlSnapshot capture(Player player) {
        return new PlayerControlSnapshot(player);
    }

    void engage(Player player) {
        player.setGravity(false);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setVelocity(new Vector());
        player.setFallDistance(0.0f);
    }

    void restore(Player player) {
        player.setVelocity(new Vector());
        player.setFallDistance(fallDistance);
        player.setFlying(flying && allowFlight);
        player.setAllowFlight(allowFlight);
        player.setGravity(gravity);
        player.setVelocity(velocity);
    }
}
