/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: ForceMainThreadRebuild and MixinSodiumFlawlessFrames for Sodium 0.5, preparing resident arrival
 * terrain and completing unfinished sections on the first crossing frame.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import me.jellysquid.mods.sodium.client.world.WorldRendererExtended;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

public final class SodiumSectionDiscovery {
    private static final float ARRIVAL_MARGIN_BLOCKS = 32.0F;
    private static final int MAX_DISCOVERY_PASSES = 32;

    private SodiumSectionDiscovery() {
    }

    /** Arms the renderer to finish every visible section on its next main-view terrain setup. */
    public static void request(LevelRenderer renderer) {
        SodiumWorldRenderer world = ((WorldRendererExtended) renderer).sodium$getWorldRenderer();
        if (world != null) {
            ((SodiumPortalRenderer) world).wormholes$discoveryArmed(true);
        }
    }

    /** Queues builds around {@code position} on a renderer that no view draws yet, a frame's budget at a time. */
    public static void prepareArrival(LevelRenderer renderer, Vec3 position, boolean fullColumns) {
        SodiumWorldRenderer sodium = ((WorldRendererExtended) renderer).sodium$getWorldRenderer();
        if (sodium == null) {
            return;
        }
        SodiumLayers.managed(() -> {
            SodiumLayerTerrain.reloadIfResized(sodium);
            SodiumPortalRenderer world = (SodiumPortalRenderer) sodium;
            RenderSectionManager sections = world.wormholes$sections();
            if (sections == null) {
                return;
            }
            SodiumPortalSections internals = (SodiumPortalSections) sections;
            world.wormholes$processChunkEvents();
            sections.uploadChunks();
            long section = SectionPos.asLong(SectionPos.posToSectionCoord(position.x), SectionPos.posToSectionCoord(position.y),
                SectionPos.posToSectionCoord(position.z));
            SodiumArrivalPreparation preparation = internals.wormholes$arrivalPreparation();
            if (!preparation.needed(section, internals.wormholes$terrainRevision()) || !world.wormholes$claimBuild(SodiumLayers.epoch())) {
                return;
            }
            float distance = internals.wormholes$buildDistance() + ARRIVAL_MARGIN_BLOCKS;
            if (fullColumns) {
                double bottom = Math.abs(position.y - internals.wormholes$level().getMinBuildHeight());
                double top = Math.abs(position.y - internals.wormholes$level().getMaxBuildHeight());
                distance = Math.max(distance, (float) Math.max(bottom, top) + ARRIVAL_MARGIN_BLOCKS);
            }
            Viewport open = new Viewport(SodiumOpenFrustum.INSTANCE, new Vector3d(position.x, position.y, position.z));
            SodiumSectionCollector.Result arrival = SodiumSectionCollector.collect(internals, open, false, null, distance,
                SodiumLayers.nextFrame(), true);
            internals.wormholes$rebuildLists(arrival.rebuilds());
            sections.updateChunks(false);
            preparation.prepared(section, internals.wormholes$terrainRevision(), arrival.complete());
        });
    }

    /** Runs at the tail of the main view's terrain setup, inside Sodium's managed device context. */
    public static void terrainReady(SodiumWorldRenderer renderer, Camera camera, Viewport viewport, boolean spectator) {
        SodiumPortalRenderer world = (SodiumPortalRenderer) renderer;
        if (!world.wormholes$discoveryArmed() || SodiumLayers.slot() != 0) {
            return;
        }
        RenderSectionManager sections = world.wormholes$sections();
        SodiumPortalSections internals = (SodiumPortalSections) sections;
        for (int pass = 0; pass < MAX_DISCOVERY_PASSES; pass++) {
            sections.update(camera, viewport, SodiumLayers.nextFrame(), spectator);
            if (!SodiumSectionCollector.pending(internals.wormholes$rebuildLists())) {
                break;
            }
            sections.updateChunks(true);
            sections.uploadChunks();
        }
        sections.markGraphDirty();
        world.wormholes$discoveryArmed(false);
    }
}
