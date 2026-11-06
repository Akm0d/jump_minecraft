package com.jump.minecraft;

import java.util.List;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

final class TerrainCollisionObserver {
    private final boolean enabled;
    private final double maxDistanceBlocks;

    TerrainCollisionObserver(boolean enabled, double maxDistanceBlocks) {
        this.enabled = enabled;
        this.maxDistanceBlocks = maxDistanceBlocks;
    }

    TerrainCollisionObservation observe(
            World world,
            Location from,
            Location to,
            CoordinateMapper mapper,
            long findex) {
        return observe(world, null, from, to, mapper, findex);
    }

    TerrainCollisionObservation observe(
            World world,
            Player player,
            Location from,
            Location to,
            CoordinateMapper mapper,
            long findex) {
        if (!enabled || from.getWorld() != world || to.getWorld() != world) return null;
        Vector delta = to.toVector().subtract(from.toVector());
        double distance = delta.length();
        if (distance < 1.0e-6) return null;
        RayTraceResult hit = closestHit(world, from, delta, distance, sampleOffsets(player, from));
        if (hit == null || hit.getHitPosition() == null || hit.getHitBlockFace() == null) return null;
        Vec3 minecraftPoint = vector(hit.getHitPosition());
        Vec3 minecraftNormal = vector(hit.getHitBlockFace().getDirection()).normalized();
        return new TerrainCollisionObservation(
                findex,
                minecraftPoint,
                minecraftNormal,
                mapper.toFlightPosition(minecraftPoint),
                mapper.toFlightDirection(minecraftNormal).normalized(),
                System.nanoTime());
    }

    private RayTraceResult closestHit(
            World world,
            Location from,
            Vector delta,
            double distance,
            List<Vector> offsets) {
        Vector direction = delta.clone().normalize();
        RayTraceResult closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (Vector offset : offsets) {
            Location start = from.clone().add(offset);
            RayTraceResult candidate = world.rayTraceBlocks(
                    start,
                    direction,
                    Math.min(distance, maxDistanceBlocks),
                    FluidCollisionMode.NEVER,
                    true);
            if (candidate == null || candidate.getHitPosition() == null) continue;
            double candidateDistance = candidate.getHitPosition().distanceSquared(start.toVector());
            if (candidateDistance < closestDistance) {
                closest = candidate;
                closestDistance = candidateDistance;
            }
        }
        return closest;
    }

    private List<Vector> sampleOffsets(Player player, Location origin) {
        if (player == null) return List.of(new Vector());
        BoundingBox bounds = player.getBoundingBox();
        if (bounds == null) return List.of(new Vector());
        double centerY = (bounds.getMinY() + bounds.getMaxY()) * 0.5 - origin.getY();
        double radiusX = Math.max(0.0, (bounds.getMaxX() - bounds.getMinX()) * 0.4);
        double radiusZ = Math.max(0.0, (bounds.getMaxZ() - bounds.getMinZ()) * 0.4);
        double lowerY = bounds.getMinY() + 0.1 - origin.getY();
        double upperY = bounds.getMaxY() - 0.1 - origin.getY();
        return List.of(
                new Vector(0, lowerY, 0),
                new Vector(0, centerY, 0),
                new Vector(0, upperY, 0),
                new Vector(radiusX, centerY, 0),
                new Vector(-radiusX, centerY, 0),
                new Vector(0, centerY, radiusZ),
                new Vector(0, centerY, -radiusZ));
    }

    private static Vec3 vector(Vector value) {
        return new Vec3(value.getX(), value.getY(), value.getZ());
    }
}
