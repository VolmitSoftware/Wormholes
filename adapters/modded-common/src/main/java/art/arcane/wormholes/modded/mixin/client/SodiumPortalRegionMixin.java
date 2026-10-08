/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: MixinSodiumRenderRegion for Sodium 0.9, handing each open portal layer its own render list and
 * draw batches so that a layer never resets what an enclosing layer or the main view is about to draw.
 */
package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumLayers;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalRegion;
import art.arcane.wormholes.modded.client.render.sodium.SodiumRegionLayers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Pseudo
@Mixin(value = RenderRegion.class, remap = false)
public abstract class SodiumPortalRegionMixin implements SodiumPortalRegion {
    @Shadow @Final private ChunkRenderList renderList;
    @Shadow @Final private Map<TerrainRenderPass, MultiDrawBatch> cachedBatches;
    @Unique private SodiumRegionLayers wormholes$layers;

    @Override
    public void wormholes$listChanged(ChunkRenderList list) {
        if (wormholes$layers != null && wormholes$layers.clear(list)) {
            return;
        }
        for (MultiDrawBatch batch : cachedBatches.values()) {
            batch.clear();
        }
    }

    @ModifyReturnValue(method = "getRenderList", at = @At("RETURN"))
    private ChunkRenderList wormholes$layerList(ChunkRenderList list) {
        int slot = SodiumLayers.slot();
        return slot > 0 ? wormholes$layers().list(slot, list) : list;
    }

    @Inject(method = "getCachedBatch", at = @At("HEAD"), cancellable = true)
    private void wormholes$layerBatch(TerrainRenderPass pass, CallbackInfoReturnable<MultiDrawBatch> callback) {
        int slot = SodiumLayers.slot();
        if (slot > 0) {
            callback.setReturnValue(wormholes$layers().batch(slot, renderList, pass));
        }
    }

    @Inject(method = {"clearAllCachedBatches", "onGeometryBufferChange"}, at = @At("HEAD"))
    private void wormholes$geometryChanged(CallbackInfo callback) {
        if (wormholes$layers != null) {
            wormholes$layers.clearAll();
        }
    }

    @Inject(method = "onGeometrySegmentChange", at = @At("HEAD"))
    private void wormholes$geometrySegmentChanged(int owner, CallbackInfo callback) {
        if (wormholes$layers != null) {
            wormholes$layers.clearAll();
        }
    }

    @Inject(method = "onIndexBufferChange", at = @At("HEAD"))
    private void wormholes$indicesChanged(CallbackInfo callback) {
        if (wormholes$layers != null) {
            wormholes$layers.clear(DefaultTerrainRenderPasses.TRANSLUCENT);
        }
    }

    @Inject(method = "onIndexSegmentChange", at = @At("HEAD"))
    private void wormholes$indexSegmentChanged(int owner, CallbackInfo callback) {
        if (wormholes$layers != null) {
            wormholes$layers.clear(DefaultTerrainRenderPasses.TRANSLUCENT);
        }
    }

    @Inject(method = "clearCachedBatchFor", at = @At("HEAD"))
    private void wormholes$clearLayerPass(TerrainRenderPass pass, CallbackInfo callback) {
        if (wormholes$layers != null) {
            wormholes$layers.clear(pass);
        }
    }

    @Inject(method = "delete", at = @At("TAIL"))
    private void wormholes$deleteLayers(CallbackInfo callback) {
        if (wormholes$layers != null) {
            wormholes$layers.delete();
            wormholes$layers = null;
        }
    }

    @Unique
    private SodiumRegionLayers wormholes$layers() {
        if (wormholes$layers == null) {
            wormholes$layers = new SodiumRegionLayers((RenderRegion) (Object) this);
        }
        return wormholes$layers;
    }
}
