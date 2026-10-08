/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: RendererUsingStencil and PortalRenderer driven from the 26.x main level pass, drawing the portal
 * opening with the Wormholes aperture mesh and handing terrain and shader specifics to backends; with a deferred shader
 * backend the IrisPortalRenderer flow renders each layer after the level and composites it from per-layer framebuffers.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesClientConfig;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalGpuMesh;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public final class PortalStencilRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PortalStencilRenderer INSTANCE = new PortalStencilRenderer();
    private static final int MAX_DEPTH = 8;
    private static final int MAX_VIEWS_PER_FRAME = 12;
    private static final long FAILURE_RETRY_MILLIS = 5_000L;
    private static final double NEAR_CULL_BLOCKS = 0.5D;
    private static final double SHARED_MARGIN_CHUNKS = 1.0D;
    private static final Matrix4f IDENTITY = new Matrix4f();

    private final StencilLayers layers = new StencilLayers(MAX_DEPTH);
    private final PortalWorldRenderer world = new PortalWorldRenderer(MAX_DEPTH);
    private final DeferredLayers deferred = new DeferredLayers(MAX_DEPTH);
    private final Matrix4f projection = new Matrix4f();
    private final List<PortalView> claimed = new ArrayList<>();
    private final List<List<Candidate>> candidates = new ArrayList<>();
    private boolean active;
    private boolean deferredFrame;
    private Camera shadedCamera;
    private boolean pipelinesReady;
    private int pipelineGeneration = -1;
    private Vec3 homeEye = Vec3.ZERO;

    private PortalStencilRenderer() {
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            candidates.add(new ArrayList<>());
        }
    }

    public static PortalStencilRenderer instance() {
        return INSTANCE;
    }

    public void captureProjection(Matrix4fc matrix) {
        if (!layers.nested()) {
            projection.set(matrix);
        }
    }

    public boolean nested() {
        return layers.nested() || shadedCamera != null;
    }

    public boolean deferredActive() {
        return active && deferredFrame;
    }

    public boolean active() {
        return active;
    }

    public void extracted(ClientLevel level, LevelRenderState state, Camera camera, float partialTicks) {
        WormholesClient client = WormholesClient.instance();
        if (!active || client == null || level == null) {
            return;
        }
        CrossPortalEntities.outer(client.portalViews().current(), level, state, partialTicks);
    }

    public boolean sharedLayer() {
        return layers.nested() && world.shared();
    }

    public Camera camera() {
        if (shadedCamera != null) {
            return shadedCamera;
        }
        return layers.nested() ? world.camera() : null;
    }

    public String debugLine() {
        return "stencil=" + (active ? deferredFrame ? "deferred" : "on" : "off") + " views=" + claimed.size() + " backends=" + PortalBackends.describe();
    }

    public boolean claims(ApertureDescriptor geometry) {
        for (PortalView view : claimed) {
            if (view.surface().sameOpening(geometry)) {
                return true;
            }
        }
        return false;
    }

    public void clear() {
        claimed.clear();
        for (List<Candidate> list : candidates) {
            list.clear();
        }
        world.close();
        deferred.close();
        active = false;
        deferredFrame = false;
        pipelineGeneration = -1;
    }

    public void renderPortals(LevelRenderer renderer, CameraRenderState camera) {
        if (!layers.nested()) {
            active = beginFrame(renderer, camera);
            deferredFrame = active && PortalBackends.pipeline().deferred();
        }
        if (!active || deferredFrame) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<Candidate> visible = candidates.get(layers.depth());
        visible.clear();
        collect(minecraft.level, camera, visible);
        if (visible.isEmpty()) {
            return;
        }
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        if (layers.claimStencilClear()) {
            try (RenderPass pass = open(main, "Wormholes portal stencil clear")) {
                PortalStencil.clear();
            }
        }
        int outer = layers.reference();
        long now = System.currentTimeMillis();
        WormholesClient client = WormholesClient.instance();
        int subdivisions = client == null ? WormholesClientConfig.DEFAULT_PORTAL_SHAPE_SUBDIVISIONS : client.config().portalShapeSubdivisions;
        try {
            for (Candidate candidate : visible) {
                PortalView view = candidate.view();
                if (!layers.canEnter(view.recursion()) || !layers.claimView()) {
                    continue;
                }
                try {
                    renderView(view, camera, outer, main, subdivisions);
                } catch (RuntimeException failure) {
                    view.failed(now + FAILURE_RETRY_MILLIS);
                    LOGGER.error("Unable to render the {} portal view into {}", view.kind(), view.destination().dimension().identifier(), failure);
                }
            }
        } finally {
            visible.clear();
            PortalStencil.restore(outer);
        }
    }

    public void renderDeferredPortals(CameraRenderState camera) {
        if (!active || !deferredFrame) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int depth = layers.depth();
        List<Candidate> visible = candidates.get(depth);
        visible.clear();
        collect(minecraft.level, camera, visible);
        boolean meshes = depth == 0 && ClientPortalRenderer.instance().shadedViews();
        if (visible.isEmpty() && !meshes) {
            return;
        }
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        long now = System.currentTimeMillis();
        WormholesClient client = WormholesClient.instance();
        int subdivisions = client == null ? WormholesClientConfig.DEFAULT_PORTAL_SHAPE_SUBDIVISIONS : client.config().portalShapeSubdivisions;
        try {
            deferred.capture(main, depth);
            for (Candidate candidate : visible) {
                PortalView view = candidate.view();
                if (!layers.canEnter(view.recursion()) || !layers.claimView()) {
                    continue;
                }
                try {
                    renderDeferredView(view, camera, depth, main, subdivisions);
                } catch (RuntimeException failure) {
                    view.failed(now + FAILURE_RETRY_MILLIS);
                    LOGGER.error("Unable to render the {} portal view into {}", view.kind(), view.destination().dimension().identifier(), failure);
                }
            }
            if (meshes) {
                ClientPortalRenderer.instance().renderShadedViews();
            }
            if (depth == 0) {
                deferred.finish(main);
            }
        } finally {
            visible.clear();
            PortalStencil.restore(0);
        }
    }

    public void shadedView(PortalGpuMesh aperture, boolean shaped, Matrix4f apertureView, Camera camera, Runnable render) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        deferred.mark(0, aperture, shaped, apertureView, projection, null);
        deferred.forget(1);
        shadedCamera = camera;
        try {
            render.run();
        } finally {
            shadedCamera = null;
        }
        deferred.composite(main, 1, 0);
    }

    public boolean clearLayer(Vector4fc fogColor) {
        if (!layers.nested() || deferredFrame) {
            return false;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try (RenderPass pass = open(main, "Wormholes portal layer clear")) {
            pass.setPipeline(compiled(StencilPipelines.CLEAR));
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(),
                new Vector4f(fogColor.x(), fogColor.y(), fogColor.z(), 0.0F)));
            pass.draw(3, 1, 0, 0);
        }
        return true;
    }

    private boolean beginFrame(LevelRenderer renderer, CameraRenderState camera) {
        claimed.clear();
        Minecraft minecraft = Minecraft.getInstance();
        WormholesClient client = WormholesClient.instance();
        LocalPlayer player = minecraft.player;
        if (client == null || player == null || minecraft.level == null || !(player.level() instanceof ClientLevel home)) {
            return false;
        }
        homeEye = camera.pos;
        Vec3 eye = player.getEyePosition();
        WormholesClientConfig config = client.config();
        client.portalViews().frame(home, new Vec3d(eye.x, eye.y, eye.z), client.seamlessTravel().arms(),
            config.clientMirror && client.session().active() && client.session().has(ViewStreamCapability.CLIENT_MIRROR)
                && client.viewsAttachedTo(home), config.clientRecursion,
            client.session().portals().values(), client.seamlessTravel().crossing());
        world.beginFrame(minecraft.level, renderer, minecraft.levelExtractor);
        if (!available(minecraft)) {
            return false;
        }
        layers.beginFrame(MAX_VIEWS_PER_FRAME);
        long now = System.currentTimeMillis();
        for (PortalView view : client.portalViews().current()) {
            if ((view.kind() == PortalView.Kind.ARM || view.kind() == PortalView.Kind.MIRROR) && view.source() == minecraft.level
                && renderable(view, now)) {
                claimed.add(view);
            }
        }
        return true;
    }

    private boolean available(Minecraft minecraft) {
        if (!PortalBackends.available() || !PortalStencil.available(minecraft.gameRenderer.mainRenderTarget())) {
            return false;
        }
        int generation = PortalClipShaders.generation();
        if (generation != pipelineGeneration) {
            pipelineGeneration = generation;
            world.close();
            deferred.close();
            pipelinesReady = true;
            for (RenderPipeline pipeline : StencilPipelines.ALL) {
                if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
                    pipelinesReady = false;
                    LOGGER.warn("Portal views fall back to streamed meshes: pipeline {} did not compile", pipeline.getLocation());
                }
            }
        }
        return pipelinesReady;
    }

    private void collect(ClientLevel level, CameraRenderState camera, List<Candidate> visible) {
        WormholesClient client = WormholesClient.instance();
        if (client == null || level == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        double range = minecraft.options.getEffectiveRenderDistance() * 16.0D;
        Vec3d eye = new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z);
        for (PortalView view : client.portalViews().current()) {
            if (view.source() != level || !renderable(view, now) || !view.surface().servesEye(eye)) {
                continue;
            }
            Box area = view.surface().area();
            double distance = view.surface().distance(eye);
            if (distance > range) {
                continue;
            }
            AABB bounds = new AABB(area.getXa(), area.getYa(), area.getZa(), area.getXb(), area.getYb(), area.getZb()).inflate(0.01D);
            if (distance > NEAR_CULL_BLOCKS && !camera.cullFrustum.isVisible(bounds)) {
                continue;
            }
            visible.add(new Candidate(view, distance));
        }
        visible.sort(Comparator.comparingDouble(Candidate::distance));
    }

    private boolean renderable(PortalView view, long now) {
        if (view.failing(now)) {
            return false;
        }
        ClientLevel destination = view.destination();
        if (destination != world.homeLevel()) {
            return destination == ClientWorldLoader.mainLevel() || ClientWorldLoader.residentRenderer(destination) != null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3d mapped = view.toDestination().point(view.surface().planePoint());
        double reach = (minecraft.options.getEffectiveRenderDistance() - SHARED_MARGIN_CHUNKS) * 16.0D;
        double dx = mapped.x() - homeEye.x;
        double dz = mapped.z() - homeEye.z;
        return dx * dx + dz * dz <= reach * reach;
    }

    private void renderView(PortalView view, CameraRenderState camera, int outer, RenderTarget main, int subdivisions) {
        PortalGpuMesh mesh = view.mesh(subdivisions);
        if (mesh == null) {
            return;
        }
        boolean shaped = view.shaped();
        Matrix4f aperture = apertureView(camera, view.surface());
        try (RenderPass pass = open(main, "Wormholes portal stencil mark")) {
            PortalStencil.mark(outer);
            pass.setPipeline(compiled(StencilPipelines.mask(shaped)));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(aperture));
            mesh.draw(pass);
        }
        boolean outerMirrored = layers.mirrored();
        int inner = layers.enter(PortalLayerMath.mirrored(view.toDestination()));
        try {
            PortalStencil.limit(inner);
            fill(main, StencilPipelines.FAR, "Wormholes portal far depth");
            world.render(view, camera, projection, inner, layers.mirrored(), outerMirrored);
            PortalStencil.limit(inner);
            try (RenderPass pass = open(main, "Wormholes portal stencil depth")) {
                pass.setPipeline(compiled(StencilPipelines.depth(shaped)));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(aperture));
                mesh.draw(pass);
            }
        } finally {
            layers.exit();
            PortalStencil.clamp(outer);
            fill(main, StencilPipelines.CLAMP, "Wormholes portal stencil clamp");
        }
    }

    private void renderDeferredView(PortalView view, CameraRenderState camera, int outer, RenderTarget main, int subdivisions) {
        PortalGpuMesh mesh = view.mesh(subdivisions);
        if (mesh == null) {
            return;
        }
        deferred.mark(outer, mesh, view.shaped(), apertureView(camera, view.surface()), projection, outer == 0 ? null : world.clipPlane());
        boolean outerMirrored = layers.mirrored();
        int inner = layers.enter(PortalLayerMath.mirrored(view.toDestination()));
        deferred.forget(inner);
        try {
            world.render(view, camera, projection, inner, layers.mirrored(), outerMirrored);
        } finally {
            layers.exit();
        }
        deferred.composite(main, inner, outer);
    }

    private static void fill(RenderTarget main, RenderPipeline pipeline, String label) {
        try (RenderPass pass = open(main, label)) {
            pass.setPipeline(compiled(pipeline));
            pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(IDENTITY)));
            pass.draw(3, 1, 0, 0);
        }
    }

    private static Matrix4f apertureView(CameraRenderState camera, PortalSurface surface) {
        Matrix4d relative = new Matrix4d().translation(-camera.pos.x, -camera.pos.y, -camera.pos.z).mul(surface.model());
        return new Matrix4f(camera.viewRotationMatrix).mul(new Matrix4f(relative));
    }

    private static RenderPass open(RenderTarget main, String label) {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> label, main.getColorTextureView(), Optional.empty(),
            main.getDepthTextureView(), OptionalDouble.empty());
    }

    private static CompiledRenderPipeline compiled(RenderPipeline pipeline) {
        return RenderSystem.getCompiledPipeline(pipeline);
    }

    private record Candidate(PortalView view, double distance) {
    }
}
