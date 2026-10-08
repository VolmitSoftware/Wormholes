/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: ForceMainThreadRebuild and MixinSodiumFlawlessFrames, as one synchronous build and render list
 * pass for Sodium 0.9 on the first frame after a crossing, presented without the section fade-in.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.renderer.LevelRenderer;

public final class SodiumSectionDiscovery {
    private static boolean presenting;

    private SodiumSectionDiscovery() {
    }

    public static void request(LevelRenderer renderer) {
        ((SodiumPortalRenderer) ((LevelRendererExtension) renderer).sodium$getWorldRenderer()).wormholes$discoveryArmed(true);
    }

    public static boolean presenting() {
        return presenting;
    }

    public static void terrainReady(SodiumWorldRenderer renderer, Viewport viewport, FogParameters fog) {
        SodiumPortalRenderer world = (SodiumPortalRenderer) renderer;
        if (!world.wormholes$discoveryArmed() || SodiumLayers.slot() != 0) {
            return;
        }
        world.wormholes$discoveryArmed(false);
        RenderSectionManager sections = world.wormholes$sections();
        SodiumPortalSections internals = (SodiumPortalSections) sections;
        if (internals.wormholes$culling()) {
            internals.wormholes$settleCulling();
        }
        float distance = internals.wormholes$searchDistance(fog);
        internals.wormholes$taskLists(SodiumTaskSweep.pending(internals, viewport, distance, true));
        presenting = true;
        try {
            sections.updateChunks(viewport, true);
            sections.processChunkBuilds(viewport, world.wormholes$uniforms());
        } finally {
            presenting = false;
        }
        SodiumLayerTerrain.collect(internals, sections, viewport, distance);
        sections.markGraphDirty();
    }
}
