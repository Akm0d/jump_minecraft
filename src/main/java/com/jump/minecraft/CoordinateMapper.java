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

    public Vec3 toFlightPosition(Vec3 minecraftPosition) {
        Vec3 delta = minecraftPosition.subtract(minecraftOrigin);
        Vec3 unscaled = new Vec3(delta.x() / scale, delta.y() / scale, delta.z() / scale);
        Vec3 unrotated = inverseRotate(unscaled);
        return new Vec3(
                jumpOrigin.x() + unrotated.x(),
                jumpOrigin.y() + unrotated.y(),
                jumpOrigin.z() + unrotated.z());
    }

    public Vec3 toFlightDirection(Vec3 minecraftDirection) {
        return inverseRotate(minecraftDirection);
    }

    private Vec3 inverseRotate(Vec3 value) {
        double radians = Math.toRadians(alignmentDegrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(
                cos * value.x() + sin * value.z(),
                value.y(),
                -sin * value.x() + cos * value.z());
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
