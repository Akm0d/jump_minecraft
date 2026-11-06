package com.jump.minecraft;

record TerrainCollisionObservation(
        long findex,
        Vec3 minecraftPoint,
        Vec3 minecraftNormal,
        Vec3 flightPoint,
        Vec3 flightNormal,
        long observedNanos) {
}
