/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the per-layer ChunkRenderList of MixinSodiumRenderRegion, extended with the per-layer draw
 * batches that Sodium 0.9 caches on each render region, kept apart for every list the region currently hands out.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

import java.util.ArrayList;
import java.util.List;

public final class SodiumRegionLayers {
    private static final int BATCH_CAPACITY = ModelQuadFacing.COUNT * 256 + 1;

    private final RenderRegion region;
    private final List<Entry> entries = new ArrayList<>(2);

    public SodiumRegionLayers(RenderRegion region) {
        this.region = region;
    }

    public ChunkRenderList list(int slot, ChunkRenderList base) {
        return entry(slot, base).list();
    }

    public MultiDrawBatch batch(int slot, ChunkRenderList base, TerrainRenderPass pass) {
        MultiDrawBatch[] batches = entry(slot, base).batches();
        int passIndex = DefaultTerrainRenderPasses.getPassIndex(pass);
        MultiDrawBatch batch = batches[passIndex];
        if (batch == null) {
            batch = MultiDrawBatch.newBatch(BATCH_CAPACITY);
            batches[passIndex] = batch;
        }
        return batch;
    }

    public boolean clear(ChunkRenderList list) {
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.list() == list) {
                clear(entry.batches());
                return true;
            }
        }
        return false;
    }

    public void clear(TerrainRenderPass pass) {
        int passIndex = DefaultTerrainRenderPasses.getPassIndex(pass);
        for (int index = 0; index < entries.size(); index++) {
            MultiDrawBatch batch = entries.get(index).batches()[passIndex];
            if (batch != null) {
                batch.clear();
            }
        }
    }

    public void clearAll() {
        for (int index = 0; index < entries.size(); index++) {
            clear(entries.get(index).batches());
        }
    }

    public void delete() {
        for (int index = 0; index < entries.size(); index++) {
            for (MultiDrawBatch batch : entries.get(index).batches()) {
                if (batch != null) {
                    batch.delete();
                }
            }
        }
        entries.clear();
    }

    private Entry entry(int slot, ChunkRenderList base) {
        if (slot < 1) {
            throw new IllegalArgumentException("Portal layer slot must be positive, got " + slot);
        }
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            if (entry.slot() == slot && entry.base() == base) {
                return entry;
            }
        }
        Entry entry = new Entry(slot, base, new ChunkRenderList(region), new MultiDrawBatch[DefaultTerrainRenderPasses.ALL.length]);
        entries.add(entry);
        return entry;
    }

    private static void clear(MultiDrawBatch[] batches) {
        for (MultiDrawBatch batch : batches) {
            if (batch != null) {
                batch.clear();
            }
        }
    }

    private record Entry(int slot, ChunkRenderList base, ChunkRenderList list, MultiDrawBatch[] batches) {
    }
}
