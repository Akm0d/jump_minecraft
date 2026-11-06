package com.jump.minecraft;

public record Vec3(double x, double y, double z) {
    public Vec3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Vector components must be finite");
        }
    }

    public Vec3 subtract(Vec3 other) {
        return new Vec3(x - other.x, y - other.y, z - other.z);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public Vec3 normalized() {
        double magnitude = length();
        return magnitude < 1.0e-12
                ? new Vec3(0.0, 0.0, 0.0)
                : new Vec3(x / magnitude, y / magnitude, z / magnitude);
    }
}
