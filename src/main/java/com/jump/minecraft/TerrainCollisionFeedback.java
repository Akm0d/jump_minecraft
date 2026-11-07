package com.jump.minecraft;

record TerrainCollisionFeedback(Vec3 response, Vec3 hitPoint, Vec3 hitNormal) {
    TerrainCollisionFeedback {
        if (response == null || hitPoint == null || hitNormal == null
                || !finite(response) || !finite(hitPoint) || !finite(hitNormal)) {
            throw new IllegalArgumentException("collision feedback must contain finite vectors");
        }
        double normalLength = hitNormal.length();
        if (normalLength < 0.99 || normalLength > 1.01) {
            throw new IllegalArgumentException("collision normal must be normalized");
        }
    }

    static TerrainCollisionFeedback contact(TerrainCollisionObservation observation, double acceleration) {
        Vec3 normal = observation.flightNormal().normalized();
        return new TerrainCollisionFeedback(
                new Vec3(normal.x() * acceleration, normal.y() * acceleration, normal.z() * acceleration),
                observation.flightPoint(),
                normal);
    }

    static TerrainCollisionFeedback neutral() {
        return new TerrainCollisionFeedback(
                new Vec3(0, 0, 0), new Vec3(-1000, -1000, -1000), new Vec3(0, 1, 0));
    }

    String toJson() {
        return "{\"response\":" + vector(response)
                + ",\"hitPoint\":" + vector(hitPoint)
                + ",\"hitNormal\":" + vector(hitNormal) + "}";
    }

    private static String vector(Vec3 value) {
        return "{\"x\":" + value.x() + ",\"y\":" + value.y() + ",\"z\":" + value.z() + "}";
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }
}
