/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: MixinSodiumRenderRegion for Sodium 0.5, handing each open portal layer its own render list.
 */
package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumLayers;
import art.arcane.wormholes.modded.client.render.sodium.SodiumRegionLayers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(value = RenderRegion.class, remap = false)
public abstract class SodiumPortalRegionMixin {
    @Unique private SodiumRegionLayers wormholes$layers;

    @ModifyReturnValue(method = "getRenderList", at = @At("RETURN"))
    private ChunkRenderList wormholes$layerList(ChunkRenderList list) {
        int slot = SodiumLayers.slot();
        if (slot <= 0) {
            return list;
        }
        if (wormholes$layers == null) {
            wormholes$layers = new SodiumRegionLayers((RenderRegion) (Object) this);
        }
        return wormholes$layers.list(slot);
    }
}
