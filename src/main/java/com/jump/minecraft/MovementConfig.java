package com.jump.minecraft;

public record MovementConfig(
        long staleAfterMillis,
        double blocksPerMeter,
        double headingOffsetDegrees,
        double maxStepBlocks,
        int preloadLookaheadSteps,
        int maxChunksPerStation,
        int maxConcurrentChunkLoads,
        int maxRetainedChunks,
        boolean collisionObservationEnabled,
        double collisionMaxDistanceBlocks) {

    public MovementConfig(
            long staleAfterMillis,
            double blocksPerMeter,
            double headingOffsetDegrees,
            double maxStepBlocks) {
        this(staleAfterMillis, blocksPerMeter, headingOffsetDegrees, maxStepBlocks,
                6, 12, 8, 48, true, 32.0);
    }

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
        if (preloadLookaheadSteps < 1 || maxChunksPerStation < 1
                || maxConcurrentChunkLoads < 1 || maxRetainedChunks < 1) {
            throw new IllegalArgumentException("Chunk loading limits must be positive");
        }
        if (maxChunksPerStation > maxRetainedChunks) {
            throw new IllegalArgumentException("Per-station chunk limit exceeds global retained limit");
        }
        if (!(collisionMaxDistanceBlocks > 0.0) || !Double.isFinite(collisionMaxDistanceBlocks)) {
            throw new IllegalArgumentException("Collision observation distance must be finite and positive");
        }
    }
}
