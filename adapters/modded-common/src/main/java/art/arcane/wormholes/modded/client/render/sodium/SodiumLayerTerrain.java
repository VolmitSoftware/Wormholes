/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: SodiumInterface.switchContextWithCurrentWorldRenderer and MixinSodiumRenderSectionManager for
 * Sodium 0.9, swapping the render lists, culling tree, fog, terrain uniforms and draw flags of the layer's own Sodium
 * renderer and building a synchronous, plane-clipped render list for the portal camera.
 */
package art.arcane.wormholes.modded.client.render.sodium;

import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.FallbackVisibleChunkCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.trigger.CameraMovement;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.SimpleFrustum;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.caffeinemc.mods.sodium.client.world.LevelRendererExtension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3d;

final class SodiumLayerTerrain implements LayerContextStack.Context<SodiumTerrainState> {
    private static final long SWEEP_INTERVAL_NANOS = 100_000_000L;

    private final PortalLayer layer;
    private final LevelRendererExtension renderer;
    private final SodiumPortalRenderer world;
    private final RenderSectionManager sections;
    private final SodiumPortalSections internals;
    private final UniformBufferManager uniforms;
    private final SodiumPortalUniforms buffers;

    SodiumLayerTerrain(PortalLayer layer, LevelRendererExtension renderer) {
        this.layer = layer;
        this.renderer = renderer;
        world = (SodiumPortalRenderer) renderer.sodium$getWorldRenderer();
        sections = world.wormholes$sections();
        if (sections == null) {
            throw new IllegalStateException("Sodium has no terrain for " + layer.level().dimension().identifier());
        }
        internals = (SodiumPortalSections) sections;
        uniforms = world.wormholes$uniforms();
        buffers = (SodiumPortalUniforms) uniforms;
    }

    static void reloadIfResized(SodiumWorldRenderer renderer) {
        if (((SodiumPortalRenderer) renderer).wormholes$renderDistance() != Minecraft.getInstance().options.getEffectiveRenderDistance()) {
            renderer.reload();
        }
    }

    static void collect(SodiumPortalSections internals, RenderSectionManager sections, Viewport viewport, float distance) {
        FallbackVisibleChunkCollector visible = new FallbackVisibleChunkCollector(viewport, distance, SodiumLayers.nextFrame(),
            internals.wormholes$storage(), sections.regions, internals.wormholes$level());
        internals.wormholes$renderable().prepareForTraversal();
        internals.wormholes$renderable().traverse(visible, viewport, distance);
        internals.wormholes$renderLists(visible.createRenderLists(viewport));
        visible.prepareForTraversal();
        internals.wormholes$renderTree(visible);
    }

    PortalLayer layer() {
        return layer;
    }

    @Override
    public SodiumTerrainState capture() {
        boolean[] draws = sections.getChunkRenderer() instanceof SodiumPortalDraws chunks ? chunks.wormholes$draws() : null;
        return new SodiumTerrainState(internals.wormholes$renderLists(), internals.wormholes$renderTree(), world.wormholes$fog(),
            buffers.wormholes$data(), buffers.wormholes$written(), renderer.sodium$getMatrices(), draws);
    }

    @Override
    public void restore(SodiumTerrainState state) {
        internals.wormholes$renderLists(state.lists());
        internals.wormholes$renderTree(state.tree());
        world.wormholes$fog(state.fog());
        buffers.wormholes$data(state.uniforms());
        buffers.wormholes$written(state.uniformsWritten());
        renderer.sodium$setMatrices(state.matrices());
        if (state.draws() != null && sections.getChunkRenderer() instanceof SodiumPortalDraws chunks) {
            chunks.wormholes$draws(state.draws());
        }
    }

    void prepare() {
        CameraRenderState camera = layer.camera();
        Vec3 position = camera.pos;
        Vector3d origin = new Vector3d(position.x, position.y, position.z);
        FrustumIntersection frustum = new FrustumIntersection(new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix));
        Viewport viewport = new Viewport(PortalClipFrustum.clipped(new SimpleFrustum(frustum), layer.worldClipPlane(), position.x, position.y,
            position.z), origin);
        FogParameters fog = ((GameRendererStorage) Minecraft.getInstance().gameRenderer).sodium$getFogParameters();
        world.wormholes$fog(fog);
        buffers.wormholes$written(false);
        if (!layer.shared() && world.wormholes$claimBuild()) {
            build(viewport, origin);
        }
        collect(internals, sections, viewport, internals.wormholes$searchDistance(fog));
        sections.tickVisibleRenders();
    }

    private void build(Viewport viewport, Vector3d origin) {
        if (internals.wormholes$culling()) {
            internals.wormholes$settleCulling();
        }
        world.wormholes$processChunkEvents();
        sections.prepareFrame(origin);
        Vector3d last = world.wormholes$lastCamera();
        if (last != null && !last.equals(origin)) {
            sections.processGFNIMovement(new CameraMovement(last, origin));
        }
        world.wormholes$lastCamera(origin);
        sections.prepareRender();
        sections.cleanupAndFlip(uniforms);
        long section = SectionPos.asLong(SectionPos.posToSectionCoord(origin.x), SectionPos.posToSectionCoord(origin.y),
            SectionPos.posToSectionCoord(origin.z));
        long now = System.nanoTime();
        if (section != world.wormholes$sweptSection() || sections.needsUpdate() && now - world.wormholes$sweptAt() >= SWEEP_INTERVAL_NANOS) {
            Viewport open = new Viewport(PortalClipFrustum.OPEN, origin);
            internals.wormholes$taskLists(SodiumTaskSweep.pending(internals, open, internals.wormholes$buildDistance(), false));
            world.wormholes$swept(section, now);
            internals.wormholes$graphUpdated();
        }
        sections.updateChunks(viewport, false);
        sections.processChunkBuilds(viewport, uniforms);
    }
}
