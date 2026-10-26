package com.jump.minecraft;

public final class CoordinateMapper {
    private final Vec3 minecraftOrigin;
    private final Vec3 jumpOrigin;
    private final double alignmentDegrees;
    private final double scale;

    public CoordinateMapper(
            Vec3 minecraftOrigin,
            float minecraftYaw,
            FlightFrame firstFlyingFrame,
            double scale,
            double headingOffsetDegrees) {
        if (!(scale > 0.0) || !Double.isFinite(scale)) {
            throw new IllegalArgumentException("Scale must be finite and positive");
        }
        this.minecraftOrigin = minecraftOrigin;
        this.jumpOrigin = firstFlyingFrame.position();
        this.alignmentDegrees = minecraftYaw - jumpYaw(firstFlyingFrame.forward()) + headingOffsetDegrees;
        this.scale = scale;
    }

    public MappedPose map(FlightFrame frame) {
        Vec3 delta = frame.position().subtract(jumpOrigin);
        double radians = Math.toRadians(alignmentDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double rotatedX = cos * delta.x() - sin * delta.z();
        double rotatedZ = sin * delta.x() + cos * delta.z();
        return new MappedPose(
                minecraftOrigin.x() + scale * rotatedX,
                minecraftOrigin.y() + scale * delta.y(),
                minecraftOrigin.z() + scale * rotatedZ,
                normalizeYaw((float) (jumpYaw(frame.forward()) + alignmentDegrees)),
                (float) -jumpPitch(frame.forward()));
    }

    static double jumpYaw(Vec3 forward) {
        return Math.toDegrees(Math.atan2(-forward.x(), forward.z()));
    }

    static double jumpPitch(Vec3 forward) {
        return Math.toDegrees(Math.atan2(
                forward.y(), Math.sqrt(forward.x() * forward.x() + forward.z() * forward.z())));
    }

    static float normalizeYaw(float yaw) {
        float result = yaw % 360.0f;
        if (result >= 180.0f) result -= 360.0f;
        if (result < -180.0f) result += 360.0f;
        return result;
    }
}
