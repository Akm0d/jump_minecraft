package com.jump.minecraft;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

final class PlayerControlSnapshot {
    private final boolean gravity;
    private final boolean noPhysics;
    private final boolean invulnerable;
    private final boolean collidable;
    private final boolean allowFlight;
    private final boolean flying;
    private final float fallDistance;
    private final Vector velocity;
    private final int fireTicks;
    private final int freezeTicks;
    private final int remainingAir;

    private PlayerControlSnapshot(Player player) {
        gravity = player.hasGravity();
        noPhysics = player.hasNoPhysics();
        invulnerable = player.isMarkedInvulnerable();
        collidable = player.isCollidable();
        allowFlight = player.getAllowFlight();
        flying = player.isFlying();
        fallDistance = player.getFallDistance();
        Vector currentVelocity = player.getVelocity();
        velocity = currentVelocity == null ? new Vector() : currentVelocity.clone();
        fireTicks = player.getFireTicks();
        freezeTicks = player.getFreezeTicks();
        remainingAir = player.getRemainingAir();
    }

    static PlayerControlSnapshot capture(Player player) {
        return new PlayerControlSnapshot(player);
    }

    void engageFlight(Player player) {
        protect(player);
        player.setGravity(false);
        player.setNoPhysics(true);
        player.setCollidable(false);
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setVelocity(new Vector());
        player.setFallDistance(0.0f);
    }

    void engageFree(Player player) {
        protect(player);
        player.setGravity(gravity);
        player.setNoPhysics(noPhysics);
        player.setCollidable(collidable);
        player.setFlying(false);
        player.setAllowFlight(allowFlight);
        player.setVelocity(new Vector());
        player.setFallDistance(0.0f);
    }

    void enforce(Player player, PlayerControlMode mode) {
        if (mode == PlayerControlMode.FLIGHT) engageFlight(player);
        else protect(player);
    }

    private void protect(Player player) {
        player.setInvulnerable(true);
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setRemainingAir(player.getMaximumAir());
        player.setFallDistance(0.0f);
    }

    void restore(Player player) {
        player.setVelocity(new Vector());
        player.setFallDistance(fallDistance);
        player.setFlying(flying && allowFlight);
        player.setAllowFlight(allowFlight);
        player.setGravity(gravity);
        player.setNoPhysics(noPhysics);
        player.setCollidable(collidable);
        player.setInvulnerable(invulnerable);
        player.setFireTicks(fireTicks);
        player.setFreezeTicks(freezeTicks);
        player.setRemainingAir(remainingAir);
        player.setVelocity(velocity);
    }
}
