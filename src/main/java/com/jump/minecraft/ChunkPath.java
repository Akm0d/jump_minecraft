package com.jump.minecraft;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.util.Vector;

final class ChunkPath {
    private static final double SAMPLE_BLOCKS = 8.0;

    private ChunkPath() {
    }

    static Location boundedStep(Location from, Location target, double maxStepBlocks) {
        Vector delta = target.toVector().subtract(from.toVector());
        double distance = delta.length();
        if (distance <= maxStepBlocks || distance == 0.0) return target.clone();
        Vector step = delta.multiply(maxStepBlocks / distance);
        Location result = from.clone().add(step);
        result.setYaw(target.getYaw());
        result.setPitch(target.getPitch());
        return result;
    }

    static List<ChunkPosition> upcoming(
            Location rendered,
            Location step,
            Location previousMappedTarget,
            Location mappedTarget,
            int lookaheadSteps,
            int limit) {
        LinkedHashSet<ChunkPosition> chunks = new LinkedHashSet<>();
        chunks.add(ChunkPosition.of(step.getX(), step.getZ()));

        Vector direction = mappedTarget.toVector().subtract(
                previousMappedTarget == null ? rendered.toVector() : previousMappedTarget.toVector());
        direction.setY(0.0);
        if (direction.lengthSquared() < 1.0e-9) {
            direction = step.toVector().subtract(rendered.toVector()).setY(0.0);
        }
        double forecastDistance = Math.max(
                rendered.distance(step),
                Math.min(16.0 * lookaheadSteps, direction.length() * lookaheadSteps));
        if (direction.lengthSquared() > 1.0e-9) direction.normalize().multiply(forecastDistance);
        Location forecast = step.clone().add(direction);

        double distance = Math.hypot(forecast.getX() - rendered.getX(), forecast.getZ() - rendered.getZ());
        int samples = Math.max(1, (int) Math.ceil(distance / SAMPLE_BLOCKS));
        for (int index = 1; index <= samples && chunks.size() < limit; index++) {
            double fraction = index / (double) samples;
            double x = rendered.getX() + (forecast.getX() - rendered.getX()) * fraction;
            double z = rendered.getZ() + (forecast.getZ() - rendered.getZ()) * fraction;
            chunks.add(ChunkPosition.of(x, z));
        }
        return new ArrayList<>(chunks);
    }
}
