/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: RendererUsingStencil and PortalRenderer driven from the 26.x main level pass, drawing the portal
 * opening with the Wormholes aperture mesh and handing terrain and shader specifics to backends; with a deferred shader
 * backend the IrisPortalRenderer flow renders each layer after the level and composites it from per-layer framebuffers.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesClientConfig;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalGpuMesh;
import art.arcane.wormholes.modded.client.render.PortalShaderScope;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.slf4j.Logger;
import org.lwjgl.opengl.GL11C;

import java.util.ArrayList;
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
    private final List<PortalView> activeViews = new ArrayList<>();
    private final List<PortalViewSelection.Camera> selectionStack = new ArrayList<>();
    private final List<List<PortalView>> selectedViews = new ArrayList<>();
    private final List<List<Candidate>> candidates = new ArrayList<>();
    private boolean active;
    private boolean deferredFrame;
    private Camera shadedCamera;
    private boolean pipelinesReady;
    private int pipelineGeneration = -1;
    private Vec3 homeEye = Vec3.ZERO;
    private int preparedViews;

    private PortalStencilRenderer() {
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            candidates.add(new ArrayList<>());
            selectedViews.add(new ArrayList<>());
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

    public boolean claimsChild(int streamKey) {
        WormholesClient client = WormholesClient.instance();
        if (client == null || activeViews.isEmpty()) {
            return false;
        }
        for (PortalView view : selectedViews.get(layers.depth())) {
            if (client.portalViews().streamKey(view) == streamKey) {
                return true;
            }
        }
        return false;
    }

    public void preparedViews(int views) {
        preparedViews = Math.clamp(views, 0, MAX_VIEWS_PER_FRAME);
    }

    public int streamedPreparationBudget() {
        return MAX_VIEWS_PER_FRAME - preparedViews;
    }

    public boolean canDrawShadedView() {
        return layers.canEnter(recursionCeiling()) && layers.remainingViews() > 0;
    }

    public PortalViewSelection.Camera selectionCamera(CameraRenderState camera) {
        PortalViewSelection.Camera parent = selectionStack.isEmpty() ? null : selectionStack.getLast();
        return new PortalViewSelection.Camera(new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z), camera.viewRotationMatrix, projection,
            camera.cullFrustum, parent == null ? PortalViewSelection.Window.FULL : parent.window(),
            parent == null ? null : parent.exit(), RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
    }

    private int recursionCeiling() {
        int ceiling = MAX_DEPTH;
        for (PortalView view : activeViews) {
            ceiling = Math.min(ceiling, view.recursion());
        }
        return ceiling;
    }

    private int parentStreamKey() {
        WormholesClient client = WormholesClient.instance();
        return client == null || activeViews.isEmpty() ? 0 : client.portalViews().streamKey(activeViews.getLast());
    }

    private boolean nestedMeshes() {
        return parentStreamKey() != 0 && ClientPortalRenderer.instance().nestedViews(parentStreamKey(), world.homeLevel(), layers.depth(),
            recursionCeiling());
    }

    private void renderNestedMeshes(CameraRenderState camera) {
        ClientPortalRenderer.instance().renderNested(parentStreamKey(), world.homeLevel(), camera, projection, layers.depth(), recursionCeiling());
    }

    public void clear() {
        claimed.clear();
        activeViews.clear();
        selectionStack.clear();
        preparedViews = 0;
        for (List<PortalView> selected : selectedViews) {
            selected.clear();
        }
        for (List<Candidate> list : candidates) {
            list.clear();
        }
        world.close();
        deferred.close();
        active = false;
        deferredFrame = false;
        pipelineGeneration = -1;
    }

    public void prepareFrame(LevelRenderer renderer, CameraRenderState camera) {
        if (!layers.nested()) {
            active = beginFrame(renderer, camera);
            deferredFrame = active && PortalBackends.pipeline().deferred();
        }
    }

    public void renderPortals(CameraRenderState camera) {
        if (!active || deferredFrame) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<Candidate> visible = candidates.get(layers.depth());
        visible.clear();
        collect(minecraft.level, camera, visible);
        if (visible.isEmpty()) {
            if (nestedMeshes()) {
                renderNestedMeshes(camera);
            }
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
                if (!canEnter(view) || !layers.claimView()) {
                    continue;
                }
                try {
                    renderView(view, camera, outer, main, subdivisions);
                } catch (RuntimeException failure) {
                    view.failed(now + FAILURE_RETRY_MILLIS);
                    LOGGER.error("Unable to render the {} portal view into {}", view.kind(), view.destination().dimension().identifier(), failure);
                }
            }
            if (nestedMeshes()) {
                renderNestedMeshes(camera);
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
        boolean meshes = depth == 0 ? ClientPortalRenderer.instance().shadedViews() : nestedMeshes();
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
                if (!canEnter(view) || !layers.claimView()) {
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
                if (depth == 0) {
                    ClientPortalRenderer.instance().renderShadedViews();
                } else {
                    renderNestedMeshes(camera);
                }
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
        if (!layers.canEnter(recursionCeiling()) || !layers.claimView()) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int outer = layers.depth();
        Vector4fc clipPlane = outer == 0 ? null : world.clipPlane();
        deferred.mark(outer, aperture, shaped, apertureView, projection, clipPlane);
        int inner = layers.enter(false);
        deferred.forget(inner);
        Camera previousCamera = shadedCamera;
        shadedCamera = camera;
        try {
            render.run();
            deferred.composite(main, inner, outer);
            deferred.writeDepth(outer, aperture, shaped, apertureView, projection, clipPlane);
        } finally {
            shadedCamera = previousCamera;
            layers.exit();
            PortalStencil.restore(outer);
        }
    }

    public StreamedScope streamedScope() {
        return new StreamedScope(layers.reference());
    }

    public void captureShadedLayer() {
        deferred.capture(Minecraft.getInstance().gameRenderer.mainRenderTarget(), layers.depth());
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
        activeViews.clear();
        selectionStack.clear();
        preparedViews = 0;
        for (List<PortalView> selected : selectedViews) {
            selected.clear();
        }
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
        PortalBackends.pipeline().prepareViews(camera, projection);
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
            try (PortalShaderScope scope = PortalShaderScope.rendering()) {
                for (RenderPipeline pipeline : StencilPipelines.ALL) {
                    if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
                        pipelinesReady = false;
                        LOGGER.warn("Portal views fall back to streamed meshes: pipeline {} did not compile", pipeline.getLocation());
                    }
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
        long now = System.currentTimeMillis();
        double range = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0D;
        PortalViewSelection.Camera selection = selectionCamera(camera);
        List<PortalView> nested = activeViews.isEmpty() ? List.of() : client.portalViews().children(activeViews.getLast());
        List<PortalView> selected = selectedViews.get(layers.depth());
        selected.clear();
        selected.addAll(PortalViewSelection.collect(nested, client.portalViews().current(),
            new PortalViewSelection.Options(level, layers.depth(), recursionCeiling(), range, view -> renderable(view, now)), selection));
        for (PortalView view : selected) {
            visible.add(new Candidate(view, view.surface().distance(selection.eye())));
        }
    }

    private boolean canEnter(PortalView view) {
        if (!layers.canEnter(view.recursion())) {
            return false;
        }
        for (PortalView ancestor : activeViews) {
            if (!layers.canEnter(ancestor.recursion())) {
                return false;
            }
        }
        return true;
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
        activeViews.add(view);
        selectionStack.add(PortalViewSelection.through(selectionCamera(camera), view));
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
            activeViews.removeLast();
            selectionStack.removeLast();
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
        boolean shaped = view.shaped();
        Matrix4f aperture = apertureView(camera, view.surface());
        Vector4fc clipPlane = outer == 0 ? null : world.clipPlane();
        deferred.mark(outer, mesh, shaped, aperture, projection, clipPlane);
        boolean outerMirrored = layers.mirrored();
        int inner = layers.enter(PortalLayerMath.mirrored(view.toDestination()));
        activeViews.add(view);
        selectionStack.add(PortalViewSelection.through(selectionCamera(camera), view));
        deferred.forget(inner);
        try {
            world.render(view, camera, projection, inner, layers.mirrored(), outerMirrored);
        } finally {
            activeViews.removeLast();
            selectionStack.removeLast();
            layers.exit();
        }
        deferred.composite(main, inner, outer);
        deferred.writeDepth(outer, mesh, shaped, aperture, projection, clipPlane);
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
        try (PortalShaderScope scope = PortalShaderScope.rendering()) {
            return RenderSystem.getCompiledPipeline(pipeline);
        }
    }

    public static final class StreamedScope implements AutoCloseable {
        private final IrisClipPlanes.State clipping = IrisClipPlanes.capture();
        private final boolean stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        private final int function = GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC);
        private final int reference;
        private final int valueMask = GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK);
        private final int writeMask = GL11C.glGetInteger(GL11C.GL_STENCIL_WRITEMASK);
        private final int fail = GL11C.glGetInteger(GL11C.GL_STENCIL_FAIL);
        private final int depthFail = GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_FAIL);
        private final int depthPass = GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_PASS);
        private final int frontFace = GL11C.glGetInteger(GL11C.GL_FRONT_FACE);

        private StreamedScope(int reference) {
            this.reference = reference;
            IrisClipPlanes.release();
            PortalStencil.restore(0);
            PortalStencil.clipping(false);
            PortalStencil.mirrored(false);
        }

        @Override
        public void close() {
            GL11C.glStencilFunc(function, reference, valueMask);
            GL11C.glStencilMask(writeMask);
            GL11C.glStencilOp(fail, depthFail, depthPass);
            if (stencil) {
                GL11C.glEnable(GL11C.GL_STENCIL_TEST);
            } else {
                GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            }
            GL11C.glFrontFace(frontFace);
            clipping.restore();
        }
    }

    private record Candidate(PortalView view, double distance) {
    }
}
