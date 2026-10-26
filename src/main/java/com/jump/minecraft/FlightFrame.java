package com.jump.minecraft;

public record FlightFrame(
        long findex,
        FlightState state,
        Vec3 position,
        Vec3 forward,
        Vec3 up,
        boolean hasTracking,
        double speed) {

    public FlightFrame {
        if (state == null || position == null || forward == null || up == null) {
            throw new IllegalArgumentException("Frame fields must not be null");
        }
        if (!Double.isFinite(speed)) {
            throw new IllegalArgumentException("Speed must be finite");
        }
        if (forward.length() < 1.0e-9) {
            throw new IllegalArgumentException("Forward vector must be non-zero");
        }
    }
}
