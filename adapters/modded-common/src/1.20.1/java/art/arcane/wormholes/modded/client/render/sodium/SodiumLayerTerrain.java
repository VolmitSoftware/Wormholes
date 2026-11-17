/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: SodiumInterface.switchContextWithCurrentWorldRenderer and MixinSodiumRenderSectionManager for
 * Sodium 0.5 and Embeddium 0.3, swapping the render lists of the layer's own Sodium renderer and building a synchronous,
 * plane-clipped render list for the portal camera.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.client.render.stencil.FrameCamera;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import me.jellysquid.mods.sodium.client.render.viewport.frustum.SimpleFrustum;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;

final class SodiumLayerTerrain implements LayerContextStack.Context<SodiumTerrainState> {
    private final PortalLayer layer;
    private final SodiumPortalRenderer world;
    private final RenderSectionManager sections;
    private final SodiumPortalSections internals;

    SodiumLayerTerrain(PortalLayer layer, SodiumWorldRenderer renderer) {
        this.layer = layer;
        world = (SodiumPortalRenderer) renderer;
        sections = renderer == null ? null : world.wormholes$sections();
        if (sections == null) {
            throw new IllegalStateException("Sodium has no terrain for " + layer.level().dimension().location());
        }
        internals = (SodiumPortalSections) sections;
    }

    static void reloadIfResized(SodiumWorldRenderer renderer) {
        if (renderer != null
            && ((SodiumPortalRenderer) renderer).wormholes$renderDistance() != Minecraft.getInstance().options.getEffectiveRenderDistance()) {
            renderer.reload();
        }
    }

    /** Uploads finished meshes and submits queued builds for a renderer that no main view drives. */
    static void pump(SodiumPortalRenderer world) {
        RenderSectionManager sections = world.wormholes$sections();
        world.wormholes$processChunkEvents();
        sections.uploadChunks();
        sections.updateChunks(false);
    }

    PortalLayer layer() {
        return layer;
    }

    @Override
    public SodiumTerrainState capture() {
        return new SodiumTerrainState(internals.wormholes$renderLists(), internals.wormholes$updatedFrame());
    }

    @Override
    public void restore(SodiumTerrainState state) {
        internals.wormholes$renderLists(state.lists());
        internals.wormholes$updatedFrame(state.updatedFrame());
        if (layer.shared()) {
            // The layer restamped section visibility, which the main view's entity culling reads next frame.
            sections.markGraphDirty();
        }
    }

    void prepare() {
        if (!layer.shared() && world.wormholes$claimBuild(SodiumLayers.epoch())) {
            pump(world);
        }
        FrameCamera camera = layer.camera();
        Vec3 position = camera.pos();
        Viewport viewport = new Viewport(new SimpleFrustum(new FrustumIntersection(new Matrix4f(camera.projection()).mul(camera.viewRotation()))),
            new Vector3d(position.x, position.y, position.z));
        int frame = SodiumLayers.nextFrame();
        SodiumSectionCollector.Result visible = SodiumSectionCollector.collect(internals, viewport, true, layer.worldClipPlane(),
            internals.wormholes$buildDistance(), frame, false);
        internals.wormholes$renderLists(visible.lists());
        internals.wormholes$updatedFrame(frame);
        if (!layer.shared()) {
            internals.wormholes$rebuildLists(visible.rebuilds());
        }
        sections.tickVisibleRenders();
    }
}
