package com.jump.minecraft;

import java.util.List;
import org.bukkit.World;

interface ChunkPreloader extends AutoCloseable {
    boolean prepare(String owner, World world, ChunkPosition required, List<ChunkPosition> upcoming);

    default void release(String owner) {
    }

    @Override
    default void close() {
    }

    static ChunkPreloader loadedOnly() {
        return (owner, world, required, upcoming) -> world.isChunkLoaded(required.x(), required.z());
    }
}
