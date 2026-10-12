/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: ForceMainThreadRebuild and MixinSodiumFlawlessFrames, preparing resident arrival terrain and
 * completing unfinished Sodium 0.9 sections on the first crossing frame without the section fade-in.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.locks.LockSupport;

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

    public static void prepareArrival(LevelRenderer renderer, Vec3 camera) {
        boolean previous = presenting;
        presenting = true;
        try {
            SodiumLayerTerrain.prepareArrival(renderer, camera);
        } finally {
            presenting = previous;
        }
    }

    public static void terrainReady(SodiumWorldRenderer renderer, Viewport viewport, FogParameters fog) {
        SodiumPortalRenderer world = (SodiumPortalRenderer) renderer;
        if (!world.wormholes$discoveryArmed() || SodiumLayers.slot() != 0) {
            return;
        }
        RenderSectionManager sections = world.wormholes$sections();
        SodiumPortalSections internals = (SodiumPortalSections) sections;
        if (internals.wormholes$culling()) {
            internals.wormholes$settleCulling();
        }
        float distance = internals.wormholes$searchDistance(fog);
        SodiumTaskSweep.Work work = SodiumTaskSweep.arrival(internals, viewport, distance);
        presenting = true;
        try {
            while (!work.complete()) {
                internals.wormholes$taskLists(work.pending());
                sections.updateChunks(viewport, true);
                do {
                    work.finishRunning(sections.getBuilder());
                    sections.processChunkBuilds(viewport, world.wormholes$uniforms());
                    if (work.hasRunningBuilds()) {
                        LockSupport.parkNanos(100_000L);
                    }
                } while (work.hasRunningBuilds());
                work = SodiumTaskSweep.arrival(internals, viewport, distance);
            }
        } finally {
            presenting = false;
        }
        SodiumLayerTerrain.collect(internals, sections, viewport, distance);
        sections.markGraphDirty();
        world.wormholes$discoveryArmed(false);
    }
}
