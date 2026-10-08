/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the per-layer ChunkRenderList of MixinSodiumRenderRegion, extended with the per-layer draw
 * batches that Sodium 0.9 caches on each render region.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

import java.util.Arrays;

public final class SodiumRegionLayers {
    private static final int BATCH_CAPACITY = ModelQuadFacing.COUNT * 256 + 1;

    private final RenderRegion region;
    private ChunkRenderList[] lists = new ChunkRenderList[0];
    private MultiDrawBatch[][] batches = new MultiDrawBatch[0][];

    public SodiumRegionLayers(RenderRegion region) {
        this.region = region;
    }

    public ChunkRenderList list(int slot) {
        int index = index(slot);
        ChunkRenderList list = lists[index];
        if (list == null) {
            list = new ChunkRenderList(region);
            lists[index] = list;
        }
        return list;
    }

    public MultiDrawBatch batch(int slot, TerrainRenderPass pass) {
        MultiDrawBatch[] passes = batches[index(slot)];
        int passIndex = DefaultTerrainRenderPasses.getPassIndex(pass);
        MultiDrawBatch batch = passes[passIndex];
        if (batch == null) {
            batch = MultiDrawBatch.newBatch(BATCH_CAPACITY);
            passes[passIndex] = batch;
        }
        return batch;
    }

    public void clear(int slot) {
        if (slot < 1 || slot > batches.length) {
            return;
        }
        for (MultiDrawBatch batch : batches[slot - 1]) {
            if (batch != null) {
                batch.clear();
            }
        }
    }

    public void clear(TerrainRenderPass pass) {
        int passIndex = DefaultTerrainRenderPasses.getPassIndex(pass);
        for (MultiDrawBatch[] passes : batches) {
            MultiDrawBatch batch = passes[passIndex];
            if (batch != null) {
                batch.clear();
            }
        }
    }

    public void clearAll() {
        for (MultiDrawBatch[] passes : batches) {
            for (MultiDrawBatch batch : passes) {
                if (batch != null) {
                    batch.clear();
                }
            }
        }
    }

    public void delete() {
        for (MultiDrawBatch[] passes : batches) {
            for (MultiDrawBatch batch : passes) {
                if (batch != null) {
                    batch.delete();
                }
            }
        }
        lists = new ChunkRenderList[0];
        batches = new MultiDrawBatch[0][];
    }

    private int index(int slot) {
        if (slot < 1) {
            throw new IllegalArgumentException("Portal layer slot must be positive, got " + slot);
        }
        if (slot > lists.length) {
            lists = Arrays.copyOf(lists, slot);
            MultiDrawBatch[][] grown = Arrays.copyOf(batches, slot);
            for (int index = batches.length; index < slot; index++) {
                grown[index] = new MultiDrawBatch[DefaultTerrainRenderPasses.ALL.length];
            }
            batches = grown;
        }
        return slot - 1;
    }
}
