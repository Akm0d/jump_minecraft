package com.jump.minecraft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

final class PaperChunkPreloader implements ChunkPreloader {
    private final Plugin plugin;
    private final int maxPerStation;
    private final int maxPendingGlobal;
    private final int maxRetainedGlobal;
    private final Map<String, OwnerState> owners = new LinkedHashMap<>();
    private int pendingGlobal;
    private int retainedGlobal;
    private boolean closed;

    PaperChunkPreloader(
            Plugin plugin,
            int maxPerStation,
            int maxPendingGlobal,
            int maxRetainedGlobal) {
        this.plugin = plugin;
        this.maxPerStation = maxPerStation;
        this.maxPendingGlobal = maxPendingGlobal;
        this.maxRetainedGlobal = maxRetainedGlobal;
    }

    @Override
    public boolean prepare(
            String owner,
            World world,
            ChunkPosition required,
            List<ChunkPosition> upcoming) {
        if (closed) return false;
        OwnerState state = owners.computeIfAbsent(owner, ignored -> new OwnerState());
        if (state.world != null && state.world != world) releaseState(state);
        state.world = world;

        LinkedHashSet<ChunkPosition> desired = new LinkedHashSet<>();
        desired.add(required);
        for (ChunkPosition position : upcoming) {
            if (desired.size() >= maxPerStation) break;
            desired.add(position);
        }
        state.desired = desired;
        releaseUndesired(state);
        retainLoaded(state);
        pumpLoads();
        return world.isChunkLoaded(required.x(), required.z());
    }

    @Override
    public void release(String owner) {
        OwnerState state = owners.remove(owner);
        if (state != null) releaseState(state);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (OwnerState state : new ArrayList<>(owners.values())) releaseState(state);
        owners.clear();
    }

    private void pumpLoads() {
        if (closed) return;
        for (Map.Entry<String, OwnerState> entry : owners.entrySet()) {
            OwnerState state = entry.getValue();
            if (state.world == null) continue;
            for (ChunkPosition position : state.desired) {
                if (pendingGlobal >= maxPendingGlobal || state.pending.size() >= maxPerStation) return;
                if (state.tickets.contains(position) || state.pending.contains(position)) continue;
                if (state.world.isChunkLoaded(position.x(), position.z())) {
                    retain(state, position);
                    continue;
                }
                state.pending.add(position);
                pendingGlobal++;
                String owner = entry.getKey();
                World world = state.world;
                try {
                    world.getChunkAtAsync(position.x(), position.z(), true, false,
                            ignored -> completeAsync(owner, world, position));
                } catch (RuntimeException error) {
                    state.pending.remove(position);
                    pendingGlobal--;
                }
            }
        }
    }

    private void completeAsync(String owner, World world, ChunkPosition position) {
        Runnable completion = () -> complete(owner, world, position);
        if (Bukkit.isPrimaryThread()) completion.run();
        else plugin.getServer().getScheduler().runTask(plugin, completion);
    }

    private void complete(String owner, World world, ChunkPosition position) {
        OwnerState state = owners.get(owner);
        if (state == null || state.world != world) {
            // releaseState already removed this request from the accounting.
            pumpLoads();
            return;
        }
        if (state.pending.remove(position)) pendingGlobal--;
        if (state.desired.contains(position)) retain(state, position);
        pumpLoads();
    }

    private void retainLoaded(OwnerState state) {
        for (ChunkPosition position : state.desired) {
            if (state.world.isChunkLoaded(position.x(), position.z())) retain(state, position);
        }
    }

    private void retain(OwnerState state, ChunkPosition position) {
        if (state.tickets.contains(position) || retainedGlobal >= maxRetainedGlobal) return;
        if (state.world.addPluginChunkTicket(position.x(), position.z(), plugin)) {
            state.tickets.add(position);
            retainedGlobal++;
        }
    }

    private void releaseUndesired(OwnerState state) {
        for (ChunkPosition position : new ArrayList<>(state.tickets)) {
            if (state.desired.contains(position)) continue;
            state.world.removePluginChunkTicket(position.x(), position.z(), plugin);
            state.tickets.remove(position);
            retainedGlobal--;
        }
    }

    private void releaseState(OwnerState state) {
        if (state.world != null) {
            for (ChunkPosition position : state.tickets) {
                state.world.removePluginChunkTicket(position.x(), position.z(), plugin);
                retainedGlobal--;
            }
        }
        pendingGlobal -= state.pending.size();
        if (pendingGlobal < 0) pendingGlobal = 0;
        state.pending.clear();
        state.tickets.clear();
        state.desired = Set.of();
        state.world = null;
    }

    private static final class OwnerState {
        private World world;
        private Set<ChunkPosition> desired = Set.of();
        private final Set<ChunkPosition> pending = new LinkedHashSet<>();
        private final Set<ChunkPosition> tickets = new LinkedHashSet<>();
    }
}
