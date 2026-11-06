package com.jump.minecraft;

record ChunkPosition(int x, int z) {
    static ChunkPosition of(double blockX, double blockZ) {
        return new ChunkPosition(((int) Math.floor(blockX)) >> 4, ((int) Math.floor(blockZ)) >> 4);
    }
}
