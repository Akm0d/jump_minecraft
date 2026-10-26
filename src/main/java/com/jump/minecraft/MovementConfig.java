package com.jump.minecraft;

public record MovementConfig(
        long staleAfterMillis,
        double blocksPerMeter,
        double headingOffsetDegrees,
        double maxStepBlocks) {

    public MovementConfig {
        if (staleAfterMillis <= 0) throw new IllegalArgumentException("Stale timeout must be positive");
        if (!(blocksPerMeter > 0.0) || !Double.isFinite(blocksPerMeter)) {
            throw new IllegalArgumentException("Scale must be finite and positive");
        }
        if (!Double.isFinite(headingOffsetDegrees)) {
            throw new IllegalArgumentException("Heading offset must be finite");
        }
        if (!(maxStepBlocks > 0.0) || !Double.isFinite(maxStepBlocks)) {
            throw new IllegalArgumentException("Maximum step must be finite and positive");
        }
    }
}
