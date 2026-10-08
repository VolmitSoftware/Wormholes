/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: MyGameRenderer.switchAndRenderTheWorld for the 26.x extract and render split, with per-layer
 * render state, feature dispatchers, uniforms and a camera that can roll or reflect.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.ClientWorldExtractorAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLightmapAccess;
import art.arcane.wormholes.modded.mixin.client.PortalGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.PortalLevelExtractorAccess;
import art.arcane.wormholes.modded.mixin.client.PortalLevelRendererAccess;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class PortalWorldRenderer {
    private static final double CLIP_MARGIN = 0.01D;
    private static final double SELF_HIDE_BLOCKS = 1.0D;

    private final LayerResources[] resources;
    private final Set<ClientLevel> litLevels = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ArrayDeque<LevelRenderer> renderers = new ArrayDeque<>();
    private PortalCamera camera;
    private Vector4f layerClipPlane;
    private boolean shared;
    private ClientLevel homeLevel;
    private LevelRenderer homeRenderer;
    private LevelExtractor homeExtractor;

    PortalWorldRenderer(int maxDepth) {
        resources = new LayerResources[maxDepth + 1];
    }

    void beginFrame(ClientLevel level, LevelRenderer renderer, LevelExtractor extractor) {
        homeLevel = level;
        homeRenderer = renderer;
        homeExtractor = extractor;
        litLevels.clear();
        for (LayerResources layer : resources) {
            if (layer != null) {
                layer.endFrame();
            }
        }
    }

    ClientLevel homeLevel() {
        return homeLevel;
    }

    Camera camera() {
        return camera;
    }

    Vector4fc clipPlane() {
        return layerClipPlane;
    }

    boolean shared() {
        return shared;
    }

    void close() {
        for (int depth = 0; depth < resources.length; depth++) {
            if (resources[depth] != null) {
                resources[depth].close();
                resources[depth] = null;
            }
        }
        litLevels.clear();
        renderers.clear();
        camera = null;
        layerClipPlane = null;
        shared = false;
        homeLevel = null;
        homeRenderer = null;
        homeExtractor = null;
    }

    void render(PortalView view, CameraRenderState outer, Matrix4f projection, int depth, boolean mirrored, boolean outerMirrored) {
        ClientLevel destination = view.destination();
        if (destination == homeLevel) {
            render(view, outer, projection, depth, mirrored, outerMirrored, homeRenderer, homeExtractor);
            return;
        }
        ClientWorldLoader.WorldRenderer world = ClientWorldLoader.worldRenderer(destination);
        world.attach(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        world.rendered();
        ClientWorldLoader.withWorldRenderer(destination, () -> render(view, outer, projection, depth, mirrored, outerMirrored,
            world.renderer(), world.extractor()));
    }

    private void render(PortalView view, CameraRenderState outer, Matrix4f projection, int depth, boolean mirrored, boolean outerMirrored,
                        LevelRenderer renderer, LevelExtractor extractor) {
        Minecraft minecraft = Minecraft.getInstance();
        GameRenderer gameRenderer = minecraft.gameRenderer;
        ClientLevel destination = view.destination();
        ClientLevel previousLevel = minecraft.level;
        LayerResources layer = resources(depth);
        Similarity toDestination = view.toDestination();
        Vec3d inner = PortalLayerMath.innerCamera(toDestination, new Vec3d(outer.pos.x, outer.pos.y, outer.pos.z));
        Vec3 innerPosition = new Vec3(inner.x(), inner.y(), inner.z());
        Matrix4f view3 = PortalLayerMath.innerView(outer.viewRotationMatrix, toDestination);
        Vector4d worldPlane = PortalLayerMath.destinationPlane(view.surface(), toDestination, CLIP_MARGIN);
        Vector4f clipPlane = PortalLayerMath.clipPlane(PortalLayerMath.viewPlane(worldPlane, inner, view3), projection);
        Frustum frustum = new Frustum(view3, projection);
        frustum.prepare(inner.x(), inner.y(), inner.z());
        EnvironmentAttributeProbe probe = ClientWorldLoader.portalProbe(destination, innerPosition);
        LocalPlayer player = minecraft.player;
        PortalCamera portalCamera = layer.camera();
        portalCamera.place(destination, player, innerPosition, view3, projection, frustum, probe, detached(player, destination, innerPosition));
        boolean sharedRenderer = renderer == homeRenderer || renderers.contains(renderer);
        LevelRenderState state = layer.state();
        PortalLevelRendererAccess rendererAccess = (PortalLevelRendererAccess) renderer;
        PortalLevelExtractorAccess extractorAccess = (PortalLevelExtractorAccess) extractor;
        PortalGameRendererAccess gameAccess = (PortalGameRendererAccess) gameRenderer;
        ClientWorldGameRendererAccess lightAccess = (ClientWorldGameRendererAccess) gameRenderer;
        LevelRenderState previousRendererState = rendererAccess.wormholes$portalState();
        LevelRenderState previousExtractorState = extractorAccess.wormholes$portalState();
        LevelTargetBundle previousTargets = rendererAccess.wormholes$targets();
        SubmitNodeStorage previousSubmits = rendererAccess.wormholes$submits();
        FeatureRenderDispatcher previousFeatures = rendererAccess.wormholes$features();
        ObjectArrayList<SectionRenderDispatcher.RenderSection> previousVisible = renderer.visibleSections();
        ObjectArrayList<SectionRenderDispatcher.RenderSection> previousNearby = renderer.nearbyVisibleSections();
        boolean previousOutline = rendererAccess.wormholes$entityOutline();
        Lightmap previousLightmap = lightAccess.wormholes$lightmap();
        GpuBuffer previousGlobals = RenderSystem.getGlobalSettingsUniform();
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        ProjectionType previousProjectionType = RenderSystem.getProjectionType();
        GpuBufferSlice previousFog = RenderSystem.getShaderFog();
        boolean previousRenderingLevel = RenderSystem.isRenderingLevel;
        PortalCamera previousCamera = camera;
        Vector4f previousClipPlane = layerClipPlane;
        boolean previousShared = shared;
        Lighting lighting = gameAccess.wormholes$lighting();
        DeltaTracker deltaTracker = minecraft.getDeltaTracker();
        float partialTicks = deltaTracker.getGameTimeDeltaPartialTick(false);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        TerrainBackend terrain = PortalBackends.terrain();
        PipelineBackend pipeline = PortalBackends.pipeline();
        boolean deferred = pipeline.deferred();
        PortalLayer portalLayer = null;
        boolean contextPushed = false;
        boolean terrainStarted = false;
        boolean pipelineStarted = false;
        modelView.pushMatrix().identity();
        minecraft.level = destination;
        camera = portalCamera;
        layerClipPlane = clipPlane;
        shared = sharedRenderer;
        renderers.push(renderer);
        try {
            ClientWorldLoader.pushRenderContext(destination);
            contextPushed = true;
            Lightmap lightmap = ClientWorldLoader.lightmap(destination);
            if (lightmap != previousLightmap) {
                lightAccess.wormholes$lightmap(lightmap);
                refreshLightmap(gameRenderer, destination, lightmap, layer);
            }
            lighting.updateLevel(destination.dimensionType().cardinalLightType());
            FogData fog = gameAccess.wormholes$fogRenderer().setupFog(portalCamera, minecraft.options.getEffectiveRenderDistance(), deltaTracker,
                gameRenderer.bossOverlayWorldDarkening(partialTicks), destination);
            fillCamera(state.cameraRenderState, outer, portalCamera, innerPosition, view3, projection, frustum, fog);
            GpuBufferSlice fogSlice = layer.fog(fog);
            updateGlobals(layer, gameRenderer.gameRenderState(), destination, partialTicks, innerPosition);
            RenderSystem.setProjectionMatrix(layer.projection(projection, clipPlane), ProjectionType.PERSPECTIVE);
            RenderSystem.setShaderFog(fogSlice);
            ((ClientWorldLevelRendererAccess) renderer).wormholes$levelRenderState(state);
            ((ClientWorldExtractorAccess) extractor).wormholes$levelRenderState(state);
            rendererAccess.wormholes$targets(layer.targets());
            rendererAccess.wormholes$submits(layer.submits());
            rendererAccess.wormholes$features(layer.features(minecraft));
            rendererAccess.wormholes$visibleSections(layer.visible());
            rendererAccess.wormholes$nearbyVisibleSections(layer.nearby());
            PortalStencil.mirrored(mirrored);
            if (!deferred) {
                PortalStencil.clipping(true);
                PortalStencil.limit(depth);
            }
            portalLayer = new PortalLayer(depth, destination, renderer, sharedRenderer, state.cameraRenderState, worldPlane, clipPlane,
                layer.visible(), layer.nearby());
            terrain.beginLayer(portalLayer);
            terrainStarted = true;
            pipeline.beginLayer(portalLayer);
            pipelineStarted = true;
            extractor.extract(deltaTracker, portalCamera, partialTicks);
            state.playerCompiledSectionCallback = null;
            state.shouldShowEntityOutlines = false;
            if (destination != homeLevel) {
                state.particlesRenderState.reset();
            }
            CrossPortalEntities.inner(view, state, previousLevel, portalCamera, partialTicks);
            renderer.render(gameAccess.wormholes$resourcePool(), false, state.cameraRenderState, fogSlice, fog.color, true, false);
        } finally {
            if (pipelineStarted) {
                pipeline.endLayer(portalLayer);
            }
            if (terrainStarted) {
                terrain.endLayer(portalLayer);
            }
            if (!deferred) {
                PortalStencil.clipping(depth > 1);
            }
            PortalStencil.mirrored(outerMirrored);
            rendererAccess.wormholes$visibleSections(previousVisible);
            rendererAccess.wormholes$nearbyVisibleSections(previousNearby);
            rendererAccess.wormholes$features(previousFeatures);
            rendererAccess.wormholes$submits(previousSubmits);
            rendererAccess.wormholes$targets(previousTargets);
            rendererAccess.wormholes$entityOutline(previousOutline);
            ((ClientWorldExtractorAccess) extractor).wormholes$levelRenderState(previousExtractorState);
            ((ClientWorldLevelRendererAccess) renderer).wormholes$levelRenderState(previousRendererState);
            RenderSystem.isRenderingLevel = previousRenderingLevel;
            RenderSystem.setShaderFog(previousFog);
            RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);
            RenderSystem.setGlobalSettingsUniform(previousGlobals);
            lighting.updateLevel(previousLevel.dimensionType().cardinalLightType());
            lightAccess.wormholes$lightmap(previousLightmap);
            if (contextPushed) {
                ClientWorldLoader.popRenderContext();
            }
            renderers.pop();
            shared = previousShared;
            layerClipPlane = previousClipPlane;
            camera = previousCamera;
            minecraft.level = previousLevel;
            modelView.popMatrix();
            Camera restored = gameRenderer.mainCamera();
            minecraft.getEntityRenderDispatcher().prepare(restored, minecraft.crosshairPickEntity);
            minecraft.getBlockEntityRenderDispatcher().prepare(restored.position());
        }
    }

    private LayerResources resources(int depth) {
        LayerResources layer = resources[depth];
        if (layer == null) {
            layer = new LayerResources(depth);
            resources[depth] = layer;
        }
        return layer;
    }

    private void refreshLightmap(GameRenderer gameRenderer, ClientLevel destination, Lightmap lightmap, LayerResources layer) {
        if (!litLevels.add(destination)) {
            return;
        }
        ClientWorldGameRendererAccess access = (ClientWorldGameRendererAccess) gameRenderer;
        ((ClientWorldLightmapAccess) access.wormholes$lightmapExtractor()).wormholes$needsUpdate(true);
        access.wormholes$lightmapExtractor().extract(layer.light(), 1.0F);
        lightmap.render(layer.light());
    }

    private static void updateGlobals(LayerResources layer, GameRenderState game, ClientLevel destination, float partialTicks, Vec3 camera) {
        Minecraft minecraft = Minecraft.getInstance();
        layer.globals().update(minecraft.gameRenderer.mainRenderTarget().width, minecraft.gameRenderer.mainRenderTarget().height,
            game.optionsRenderState.glintStrength, destination.getGameTime(), partialTicks, game.optionsRenderState.menuBackgroundBlurriness,
            camera, game.optionsRenderState.textureFiltering == TextureFilteringMethod.RGSS);
    }

    private static void fillCamera(CameraRenderState target, CameraRenderState outer, PortalCamera camera, Vec3 position, Matrix4f view,
                                   Matrix4f projection, Frustum frustum, FogData fog) {
        target.pos = position;
        target.blockPos = BlockPos.containing(position);
        target.xRot = camera.xRot();
        target.yRot = camera.yRot();
        target.initialized = true;
        target.isPanoramicMode = false;
        target.isFrustumCaptured = true;
        target.isFirstPerson = outer.isFirstPerson;
        target.smartCull = false;
        target.orientation.set(camera.rotation());
        target.cameraEntityPartialTicks = outer.cameraEntityPartialTicks;
        target.cullFrustum = frustum;
        target.fogType = camera.getFluidInCamera();
        target.fogData = fog;
        target.hudFov = outer.hudFov;
        target.depthFar = outer.depthFar;
        target.projectionMatrix.set(projection);
        target.viewRotationMatrix.set(view);
        target.entityRenderState = outer.entityRenderState;
    }

    private static boolean detached(LocalPlayer player, ClientLevel destination, Vec3 camera) {
        return player == null || player.level() != destination || player.getEyePosition().distanceToSqr(camera) > SELF_HIDE_BLOCKS * SELF_HIDE_BLOCKS;
    }
}
