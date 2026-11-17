/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: MyGameRenderer.switchAndRenderTheWorld for 1.20.1, re-entering LevelRenderer.renderLevel with
 * a camera that can roll or reflect and saving every piece of global render state the re-entry touches.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.dh.DhPortalRendering;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.PortalFogRendererAccess;
import art.arcane.wormholes.modded.mixin.client.PortalGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.PortalLevelRendererAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Draws one portal layer by re-entering {@link LevelRenderer#renderLevel} on the destination's renderer. The shared
 * main renderer (same-level portals and mirrors) is re-entered with its Fabulous targets and transparency chain
 * detached and panoramic mode set, so every pass writes the main target under the stencil test and nothing the main
 * pass already drew into a side target is cleared.
 */
final class PortalWorldRenderer {
    static final double CLIP_MARGIN = 1.0E-5D;
    private static final double SELF_HIDE_BLOCKS = 1.0D;

    private final PortalCamera[] cameras;
    private final Set<ClientLevel> litLevels = Collections.newSetFromMap(new IdentityHashMap<>());
    private PortalCamera camera;
    private Vector4f layerClipPlane;
    private PortalView view;
    private boolean shared;
    private ClientLevel homeLevel;
    private LevelRenderer homeRenderer;

    PortalWorldRenderer(int maxDepth) {
        cameras = new PortalCamera[maxDepth + 1];
    }

    void beginFrame(ClientLevel level, LevelRenderer renderer) {
        homeLevel = level;
        homeRenderer = renderer;
        litLevels.clear();
    }

    ClientLevel homeLevel() {
        return homeLevel;
    }

    Camera camera() {
        return camera;
    }

    PortalView view() {
        return view;
    }

    Vector4fc clipPlane() {
        return layerClipPlane;
    }

    boolean shared() {
        return shared;
    }

    void close() {
        litLevels.clear();
        camera = null;
        layerClipPlane = null;
        view = null;
        shared = false;
        homeLevel = null;
        homeRenderer = null;
    }

    void render(PortalView view, FrameCamera outer, Matrix4f projection, int depth, boolean mirrored, boolean outerMirrored) {
        ClientLevel destination = view.destination();
        if (destination == homeLevel) {
            render(view, outer, projection, depth, mirrored, outerMirrored, homeRenderer);
            return;
        }
        ClientWorldLoader.WorldRenderer world = ClientWorldLoader.worldRenderer(destination);
        world.rendered();
        ClientWorldLoader.withWorldRenderer(destination, () -> render(view, outer, projection, depth, mirrored, outerMirrored, world.renderer()));
    }

    private void render(PortalView view, FrameCamera outer, Matrix4f projection, int depth, boolean mirrored, boolean outerMirrored,
                        LevelRenderer renderer) {
        Minecraft minecraft = Minecraft.getInstance();
        GameRenderer gameRenderer = minecraft.gameRenderer;
        ClientLevel destination = view.destination();
        ClientLevel previousLevel = minecraft.level;
        float partialTicks = outer.partialTicks();
        Similarity toDestination = view.toDestination();
        Vec3d inner = PortalLayerMath.innerCamera(toDestination, new Vec3d(outer.pos().x, outer.pos().y, outer.pos().z));
        Vec3 innerPosition = new Vec3(inner.x(), inner.y(), inner.z());
        Matrix4f innerView = PortalLayerMath.innerView(outer.viewRotation(), toDestination);
        Vector4d worldPlane = PortalLayerMath.destinationPlane(view.surface(), toDestination, CLIP_MARGIN);
        Vector4f viewPlane = PortalLayerMath.viewPlane(worldPlane, inner, innerView);
        Vector4f clipPlane = PortalLayerMath.clipPlane(viewPlane, projection);
        boolean clipShaders = PortalClipShaders.ready();
        Matrix4f layerProjection = clipShaders ? new Matrix4f(projection) : PortalLayerMath.oblique(projection, viewPlane);
        Frustum frustum = new Frustum(innerView, PortalLayerMath.cullingProjection(projection, false));
        frustum.prepare(inner.x(), inner.y(), inner.z());
        LocalPlayer player = minecraft.player;
        PortalCamera portalCamera = camera(depth);
        portalCamera.place(destination, player, innerPosition, innerView, showsPlayer(view, player, destination, innerPosition));
        boolean sharedRenderer = renderer == homeRenderer;
        PortalLevelRendererAccess rendererAccess = (PortalLevelRendererAccess) renderer;
        PortalGameRendererAccess gameAccess = (PortalGameRendererAccess) gameRenderer;
        PoseStack pose = new PoseStack();
        pose.last().pose().set(innerView);
        pose.last().normal().set(new Matrix3f(innerView).invert().transpose());
        FrameCamera layerCamera = new FrameCamera(portalCamera, innerPosition, innerView, layerProjection, frustum, partialTicks);
        PortalLayer layer = new PortalLayer(depth, view, destination, renderer, sharedRenderer, layerCamera, worldPlane, clipPlane);

        flush(minecraft, renderer);
        SavedState saved = new SavedState(minecraft, rendererAccess, gameAccess);
        PortalCamera previousCamera = camera;
        Vector4f previousClipPlane = layerClipPlane;
        PortalView previousView = this.view;
        boolean previousShared = shared;
        Vector4fc previousPlane = PortalClipState.current();
        TerrainBackend terrain = PortalBackends.terrain();
        PipelineBackend pipeline = PortalBackends.pipeline();
        boolean terrainStarted = false;
        boolean pipelineStarted = false;
        boolean entered = false;
        boolean pushed = false;
        try (DhPortalRendering.Scope dh = DhPortalRendering.open(view, destination, innerView, layerProjection, partialTicks, worldPlane, inner)) {
            minecraft.level = destination;
            camera = portalCamera;
            layerClipPlane = clipPlane;
            this.view = view;
            shared = sharedRenderer;
            LightTexture lightTexture = ClientWorldLoader.lightTexture(destination);
            if (lightTexture != saved.lightTexture) {
                gameAccess.wormholes$lightTexture(lightTexture);
                if (litLevels.add(destination)) {
                    lightTexture.updateLightTexture(partialTicks);
                }
            }
            gameRenderer.setPanoramicMode(true);
            rendererAccess.wormholes$transparencyChain(null);
            rendererAccess.wormholes$translucentTarget(null);
            rendererAccess.wormholes$itemEntityTarget(null);
            rendererAccess.wormholes$particlesTarget(null);
            rendererAccess.wormholes$weatherTarget(null);
            rendererAccess.wormholes$cloudsTarget(null);
            rendererAccess.wormholes$cullingFrustum(frustum);
            rendererAccess.wormholes$capturedFrustum(null);
            rendererAccess.wormholes$captureFrustum(false);
            RenderSystem.setProjectionMatrix(layerProjection, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(innerView).invert());
            PoseStack modelView = RenderSystem.getModelViewStack();
            modelView.pushPose();
            pushed = true;
            modelView.setIdentity();
            RenderSystem.applyModelViewMatrix();
            PortalStencil.mirrored(mirrored);
            PortalStencil.clipping(clipShaders);
            PortalClipState.set(clipShaders ? clipPlane : null);
            PortalStencil.limit(depth);
            terrain.beginLayer(layer);
            terrainStarted = true;
            pipeline.beginLayer(layer);
            pipelineStarted = true;
            PortalFrameHooks.enterLayer();
            entered = true;
            renderer.renderLevel(pose, partialTicks, Util.getNanos(), false, portalCamera, gameRenderer, lightTexture, layerProjection);
        } finally {
            try {
                if (entered) {
                    PortalFrameHooks.exitLayer();
                }
                try {
                    if (pipelineStarted) {
                        pipeline.endLayer(layer);
                    }
                } finally {
                    if (terrainStarted) {
                        terrain.endLayer(layer);
                    }
                }
            } finally {
                flush(minecraft, renderer);
                if (pushed) {
                    RenderSystem.getModelViewStack().popPose();
                    RenderSystem.applyModelViewMatrix();
                }
                PortalClipState.set(previousPlane == PortalClipState.NONE ? null : previousPlane);
                PortalStencil.clipping(depth > 1 && clipShaders);
                PortalStencil.mirrored(outerMirrored);
                saved.restore(minecraft, rendererAccess, gameAccess, previousLevel, partialTicks);
                shared = previousShared;
                this.view = previousView;
                layerClipPlane = previousClipPlane;
                camera = previousCamera;
                Camera restored = outer.camera();
                minecraft.getEntityRenderDispatcher().prepare(previousLevel, restored, minecraft.crosshairPickEntity);
                minecraft.getBlockEntityRenderDispatcher().prepare(previousLevel, restored, minecraft.hitResult);
            }
        }
    }

    private PortalCamera camera(int depth) {
        PortalCamera existing = cameras[depth];
        if (existing == null) {
            existing = new PortalCamera();
            cameras[depth] = existing;
        }
        return existing;
    }

    private static void flush(Minecraft minecraft, LevelRenderer renderer) {
        minecraft.renderBuffers().bufferSource().endBatch();
        ((PortalLevelRendererAccess) renderer).wormholes$renderBuffers().bufferSource().endBatch();
    }

    private static boolean showsPlayer(PortalView view, LocalPlayer player, ClientLevel destination, Vec3 camera) {
        WormholesClient client = WormholesClient.instance();
        boolean selfReflection = client == null || client.config().selfReflection;
        return selfVisible(view.kind(), selfReflection) && detached(player, destination, camera);
    }

    static boolean selfVisible(PortalView.Kind kind, boolean selfReflection) {
        return switch (kind) {
            case MIRROR -> selfReflection;
            case ARM, RETURN, CROSSING -> true;
        };
    }

    private static boolean detached(LocalPlayer player, ClientLevel destination, Vec3 camera) {
        return player == null || player.level() != destination || detached(player.getEyePosition(), camera);
    }

    static boolean detached(Vec3 eye, Vec3 camera) {
        return eye.distanceToSqr(camera) > SELF_HIDE_BLOCKS * SELF_HIDE_BLOCKS;
    }

    /** Global render state a {@code renderLevel} re-entry changes, captured before the layer and put back after it. */
    private static final class SavedState {
        private final LightTexture lightTexture;
        private final boolean panoramic;
        private final PostChain transparencyChain;
        private final RenderTarget translucentTarget;
        private final RenderTarget itemEntityTarget;
        private final RenderTarget particlesTarget;
        private final RenderTarget weatherTarget;
        private final RenderTarget cloudsTarget;
        private final Frustum cullingFrustum;
        private final Frustum capturedFrustum;
        private final boolean captureFrustum;
        private final Matrix4f projection;
        private final VertexSorting sorting;
        private final Matrix3f inverseViewRotation;
        private final float fogStart;
        private final float fogEnd;
        private final float[] fogColor;
        private final FogShape fogShape;
        private final float fogRed;
        private final float fogGreen;
        private final float fogBlue;
        private final int targetBiomeFog;
        private final int previousBiomeFog;
        private final long biomeChangedTime;

        private SavedState(Minecraft minecraft, PortalLevelRendererAccess renderer, PortalGameRendererAccess game) {
            lightTexture = game.wormholes$lightTexture();
            panoramic = minecraft.gameRenderer.isPanoramicMode();
            transparencyChain = renderer.wormholes$transparencyChain();
            translucentTarget = renderer.wormholes$translucentTarget();
            itemEntityTarget = renderer.wormholes$itemEntityTarget();
            particlesTarget = renderer.wormholes$particlesTarget();
            weatherTarget = renderer.wormholes$weatherTarget();
            cloudsTarget = renderer.wormholes$cloudsTarget();
            cullingFrustum = renderer.wormholes$cullingFrustum();
            capturedFrustum = renderer.wormholes$capturedFrustum();
            captureFrustum = renderer.wormholes$captureFrustum();
            projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            sorting = RenderSystem.getVertexSorting();
            inverseViewRotation = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
            fogStart = RenderSystem.getShaderFogStart();
            fogEnd = RenderSystem.getShaderFogEnd();
            fogColor = RenderSystem.getShaderFogColor().clone();
            fogShape = RenderSystem.getShaderFogShape();
            fogRed = PortalFogRendererAccess.wormholes$red();
            fogGreen = PortalFogRendererAccess.wormholes$green();
            fogBlue = PortalFogRendererAccess.wormholes$blue();
            targetBiomeFog = PortalFogRendererAccess.wormholes$targetBiome();
            previousBiomeFog = PortalFogRendererAccess.wormholes$previousBiome();
            biomeChangedTime = PortalFogRendererAccess.wormholes$biomeChanged();
        }

        private void restore(Minecraft minecraft, PortalLevelRendererAccess renderer, PortalGameRendererAccess game, ClientLevel level,
                             float partialTicks) {
            minecraft.level = level;
            game.wormholes$lightTexture(lightTexture);
            minecraft.gameRenderer.setPanoramicMode(panoramic);
            renderer.wormholes$transparencyChain(transparencyChain);
            renderer.wormholes$translucentTarget(translucentTarget);
            renderer.wormholes$itemEntityTarget(itemEntityTarget);
            renderer.wormholes$particlesTarget(particlesTarget);
            renderer.wormholes$weatherTarget(weatherTarget);
            renderer.wormholes$cloudsTarget(cloudsTarget);
            renderer.wormholes$cullingFrustum(cullingFrustum);
            renderer.wormholes$capturedFrustum(capturedFrustum);
            renderer.wormholes$captureFrustum(captureFrustum);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setInverseViewRotationMatrix(inverseViewRotation);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
            RenderSystem.setShaderFogShape(fogShape);
            PortalFogRendererAccess.wormholes$red(fogRed);
            PortalFogRendererAccess.wormholes$green(fogGreen);
            PortalFogRendererAccess.wormholes$blue(fogBlue);
            PortalFogRendererAccess.wormholes$targetBiome(targetBiomeFog);
            PortalFogRendererAccess.wormholes$previousBiome(previousBiomeFog);
            PortalFogRendererAccess.wormholes$biomeChanged(biomeChangedTime);
            RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
            if (level != null) {
                RenderSystem.setShaderGameTime(level.getGameTime(), partialTicks);
            }
        }
    }

    /** Re-applies the main view's entity lighting after layers changed the shader light directions. */
    static void restoreLighting(ClientLevel level, PoseStack pose) {
        if (level.effects().constantAmbientLight()) {
            Lighting.setupNetherLevel(pose.last().pose());
        } else {
            Lighting.setupLevel(pose.last().pose());
        }
    }
}
