/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: the per-layer ChunkRenderList of MixinSodiumRenderRegion, one list per open layer slot so a
 * layer never resets the list an enclosing layer or the main view is about to draw.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;

import java.util.Arrays;

public final class SodiumRegionLayers {
    private final RenderRegion region;
    private ChunkRenderList[] lists = new ChunkRenderList[2];

    public SodiumRegionLayers(RenderRegion region) {
        this.region = region;
    }

    public ChunkRenderList list(int slot) {
        if (slot < 1) {
            throw new IllegalArgumentException("Portal layer slot must be positive, got " + slot);
        }
        if (slot > lists.length) {
            lists = Arrays.copyOf(lists, Math.max(slot, lists.length * 2));
        }
        ChunkRenderList list = lists[slot - 1];
        if (list == null) {
            list = new ChunkRenderList(region);
            lists[slot - 1] = list;
        }
        return list;
    }
}
