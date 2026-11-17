/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: RendererUsingStencil and PortalRenderer driven from the 1.20.1 translucent anchor of
 * LevelRenderer.renderLevel, drawing the portal opening with the Wormholes aperture mesh and handing terrain and shader
 * specifics to backends.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.render.RenderPathDecision;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesClientConfig;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalGpuMesh;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.PortalFogRendererAccess;
import art.arcane.wormholes.network.client.TravelMessage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class PortalStencilRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PortalStencilRenderer INSTANCE = new PortalStencilRenderer();
    private static final int MAX_DEPTH = 8;
    private static final int MAX_VIEWS_PER_FRAME = 12;
    private static final long FAILURE_RETRY_MILLIS = 5_000L;
    private static final double SHARED_MARGIN_CHUNKS = 1.0D;

    private final StencilLayers layers = new StencilLayers(MAX_DEPTH);
    private final PortalWorldRenderer world = new PortalWorldRenderer(MAX_DEPTH);
    private final Matrix4f projection = new Matrix4f();
    private final List<PortalView> claimed = new ArrayList<>();
    private final List<PortalView> activeViews = new ArrayList<>();
    private final List<PortalViewSelection.Camera> selectionStack = new ArrayList<>();
    private final List<List<PortalView>> selectedViews = new ArrayList<>();
    private final List<List<Candidate>> candidates = new ArrayList<>();
    private FrameInputs inputs;
    private boolean active;
    private RenderPathDecision renderPath;
    private Vec3 homeEye = Vec3.ZERO;
    private int preparedViews;
    private int drawnViews;
    private long skippedViews;

    private PortalStencilRenderer() {
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            candidates.add(new ArrayList<>());
            selectedViews.add(new ArrayList<>());
        }
    }

    public static PortalStencilRenderer instance() {
        return INSTANCE;
    }

    /**
     * Supplies the view set, armed travels and crossing each frame. The client wiring installs its own
     * {@link PortalViews}; until then the renderer keeps a private one that only sees mirrors.
     */
    public void inputs(FrameInputs inputs) {
        if (this.inputs != null && this.inputs != inputs) {
            clear();
        }
        this.inputs = inputs;
    }

    public void captureProjection(Matrix4fc matrix) {
        if (!layers.nested()) {
            projection.set(matrix);
        }
    }

    public boolean nested() {
        return layers.nested();
    }

    /** 1.20.1 has no deferred stencil path: an active shader pack sends portals to streamed meshes instead. */
    public boolean deferredActive() {
        return false;
    }

    public boolean active() {
        return active;
    }

    public boolean sharedLayer() {
        return layers.nested() && world.shared();
    }

    /** Whether the current layer draws a level other than the one the player is in. */
    public boolean foreignLayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        return layers.nested() && player != null && Minecraft.getInstance().level != player.level();
    }

    public Camera camera() {
        return layers.nested() ? world.camera() : null;
    }

    public String debugLine() {
        return "stencil=" + (active ? "on" : "off") + " views=" + drawnViews + "/" + claimed.size() + " skipped=" + skippedViews
            + " clip=" + (PortalClipShaders.ready() ? "shader" : "oblique") + " backends=" + PortalBackends.describe();
    }

    /** Views drawn through the stencil in the most recent frame. */
    public int drawnViews() {
        return drawnViews;
    }

    public long skippedViews() {
        return skippedViews;
    }

    public void skipView() {
        skippedViews++;
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
        if (activeViews.isEmpty()) {
            return false;
        }
        PortalViews views = inputs().views();
        for (PortalView view : selectedViews.get(layers.depth())) {
            if (views.streamKey(view) == streamKey) {
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

    /** Shaded streamed views needed the 26.x deferred layers, which 1.20.1 does not have. */
    public boolean canDrawShadedView() {
        return false;
    }

    public PortalViewSelection.Camera selectionCamera(FrameCamera camera) {
        PortalViewSelection.Camera parent = selectionStack.isEmpty() ? null : selectionStack.getLast();
        return new PortalViewSelection.Camera(new Vec3d(camera.pos().x, camera.pos().y, camera.pos().z), camera.viewRotation(), projection,
            camera.frustum(), parent == null ? PortalViewSelection.Window.FULL : parent.window(), parent == null ? null : parent.exit(),
            ClientRenderAccess.zeroToOneDepth());
    }

    /** Whether the main pass should prepare and composite streamed mesh views this frame. */
    public boolean meshViews() {
        WormholesClient client = WormholesClient.instance();
        return !layers.nested() && (client == null || client.viewsAttachedTo(Minecraft.getInstance().level) && !inputs().travelViewActive());
    }

    public void clear() {
        claimed.clear();
        activeViews.clear();
        selectionStack.clear();
        preparedViews = 0;
        drawnViews = 0;
        for (List<PortalView> selected : selectedViews) {
            selected.clear();
        }
        for (List<Candidate> list : candidates) {
            list.clear();
        }
        if (inputs instanceof MirrorInputs own) {
            own.views.clear();
        }
        world.close();
        active = false;
    }

    public void prepareFrame(LevelRenderer renderer, FrameCamera camera) {
        if (!layers.nested()) {
            active = beginFrame(renderer, camera);
        }
    }

    /**
     * Draws every visible view at the current depth; returns whether any was drawn. Aperture marks of top-level views
     * stay in the stencil, under {@link PortalStencil#COVERED}, until the next frame clears it.
     */
    public boolean renderPortals(FrameCamera camera) {
        if (!active) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        List<Candidate> visible = candidates.get(layers.depth());
        visible.clear();
        collect(minecraft.level, camera, visible);
        if (visible.isEmpty()) {
            if (nestedMeshes()) {
                renderNestedMeshes(camera);
            }
            return false;
        }
        if (layers.claimStencilClear()) {
            PortalStencil.clear();
        }
        int outer = layers.reference();
        long now = System.currentTimeMillis();
        WormholesClient client = WormholesClient.instance();
        int subdivisions = client == null ? WormholesClientConfig.DEFAULT_PORTAL_SHAPE_SUBDIVISIONS : client.config().portalShapeSubdivisions;
        boolean drew = false;
        try {
            for (Candidate candidate : visible) {
                PortalView view = candidate.view();
                if (!canEnter(view) || !PortalBackends.pipeline().prepareView(view) || !layers.claimView()) {
                    continue;
                }
                try {
                    drew |= renderView(view, camera, outer, subdivisions);
                } catch (RuntimeException failure) {
                    view.failed(now + FAILURE_RETRY_MILLIS);
                    LOGGER.error("Unable to render the {} portal view into {}", view.kind(), view.destination().dimension().location(), failure);
                }
            }
            if (nestedMeshes()) {
                renderNestedMeshes(camera);
            }
        } finally {
            visible.clear();
            PortalStencil.restore(outer);
        }
        return drew;
    }

    /**
     * Inside a layer, replaces the full-target colour and depth clear of {@code renderLevel} with a fill limited to
     * the layer's stencil; returns false outside a layer so the caller clears as usual.
     */
    public boolean clearLayer() {
        if (!layers.nested()) {
            return false;
        }
        StencilPipelines.clear(PortalFogRendererAccess.wormholes$red(), PortalFogRendererAccess.wormholes$green(),
            PortalFogRendererAccess.wormholes$blue());
        return true;
    }

    /** Draws copies of entities straddling the openings: destination entities at depth 0, viewer entities inside a layer. */
    public void crossPortalEntities(FrameCamera camera, PoseStack pose) {
        if (!active) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        if (layers.nested()) {
            PortalView view = world.view();
            if (view != null) {
                CrossPortalEntities.inner(view, camera.pos(), pose, buffers, camera.partialTicks());
            }
        } else if (minecraft.level != null) {
            CrossPortalEntities.outer(inputs().views().current(), minecraft.level, camera.pos(), pose, buffers, camera.partialTicks());
        }
    }

    public StreamedScope streamedScope() {
        return new StreamedScope(layers.reference());
    }

    private FrameInputs inputs() {
        if (inputs == null) {
            inputs = new MirrorInputs();
        }
        return inputs;
    }

    private int recursionCeiling() {
        int ceiling = MAX_DEPTH;
        for (PortalView view : activeViews) {
            ceiling = Math.min(ceiling, view.recursion());
        }
        return ceiling;
    }

    private int parentStreamKey() {
        return activeViews.isEmpty() ? 0 : inputs().views().streamKey(activeViews.getLast());
    }

    private boolean nestedMeshes() {
        return parentStreamKey() != 0 && ClientPortalRenderer.instance().nestedViews(parentStreamKey(), world.homeLevel(), layers.depth(),
            recursionCeiling());
    }

    private void renderNestedMeshes(FrameCamera camera) {
        ClientPortalRenderer.instance().renderNested(parentStreamKey(), world.homeLevel(), camera, projection, layers.depth(), recursionCeiling());
    }

    private boolean beginFrame(LevelRenderer renderer, FrameCamera camera) {
        claimed.clear();
        activeViews.clear();
        selectionStack.clear();
        preparedViews = 0;
        drawnViews = 0;
        for (List<PortalView> selected : selectedViews) {
            selected.clear();
        }
        Minecraft minecraft = Minecraft.getInstance();
        WormholesClient client = WormholesClient.instance();
        LocalPlayer player = minecraft.player;
        if (client == null || player == null || minecraft.level == null || !(player.level() instanceof ClientLevel home)) {
            return false;
        }
        homeEye = camera.pos();
        Vec3 eye = player.getEyePosition();
        WormholesClientConfig config = client.config();
        FrameInputs frame = inputs();
        PortalViews views = frame.views();
        views.frame(home, new Vec3d(eye.x, eye.y, eye.z), frame.arms(),
            config.clientMirror && client.session().active() && client.session().has(ViewStreamCapability.CLIENT_MIRROR)
                && client.viewsAttachedTo(home), config.clientRecursion,
            client.session().portals().values(), frame.crossing());
        world.beginFrame(minecraft.level, renderer);
        if (!available(minecraft)) {
            return false;
        }
        PortalBackends.pipeline().prepareViews(camera, projection);
        layers.beginFrame(MAX_VIEWS_PER_FRAME);
        long now = System.currentTimeMillis();
        for (PortalView view : views.current()) {
            if ((view.kind() == PortalView.Kind.ARM || view.kind() == PortalView.Kind.MIRROR) && view.source() == minecraft.level
                && renderable(view, now)) {
                claimed.add(view);
            }
        }
        return true;
    }

    private boolean available(Minecraft minecraft) {
        RenderPathDecision decision = PortalBackends.decision();
        if (!decision.equals(renderPath)) {
            renderPath = decision;
            LOGGER.info("Portal views: {} on {}", decision.describe(), PortalBackends.describe());
        }
        return decision.stencilViews() && PortalStencil.available(ClientRenderAccess.mainTarget(minecraft));
    }

    private void collect(ClientLevel level, FrameCamera camera, List<Candidate> visible) {
        if (level == null) {
            return;
        }
        PortalViews views = inputs().views();
        long now = System.currentTimeMillis();
        double range = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0D;
        PortalViewSelection.Camera selection = selectionCamera(camera);
        List<PortalView> nested = activeViews.isEmpty() ? List.of() : views.children(activeViews.getLast());
        List<PortalView> selected = selectedViews.get(layers.depth());
        selected.clear();
        selected.addAll(PortalViewSelection.collect(nested, views.current(),
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

    private boolean renderView(PortalView view, FrameCamera camera, int outer, int subdivisions) {
        PortalGpuMesh mesh = view.mesh(subdivisions);
        if (mesh == null) {
            return false;
        }
        Matrix4f aperture = apertureView(camera, view.surface());
        PortalStencil.mark(outer);
        StencilPipelines.mask(mesh, aperture);
        boolean outerMirrored = layers.mirrored();
        int inner = layers.enter(PortalLayerMath.mirrored(view.toDestination()));
        activeViews.add(view);
        selectionStack.add(PortalViewSelection.through(selectionCamera(camera), view));
        try {
            PortalStencil.limit(inner);
            StencilPipelines.far();
            world.render(view, camera, projection, inner, layers.mirrored(), outerMirrored);
            PortalStencil.limit(inner);
            StencilPipelines.depth(mesh, aperture);
            drawnViews++;
        } finally {
            activeViews.removeLast();
            selectionStack.removeLast();
            layers.exit();
            PortalStencil.clamp(outer);
            StencilPipelines.clamp();
            PortalStencil.release();
        }
        return true;
    }

    private static Matrix4f apertureView(FrameCamera camera, PortalSurface surface) {
        Matrix4d relative = new Matrix4d().translation(-camera.pos().x, -camera.pos().y, -camera.pos().z).mul(surface.model());
        return new Matrix4f(camera.viewRotation()).mul(new Matrix4f(relative));
    }

    /** What one frame of portal views is built from; the client wiring provides it. */
    public interface FrameInputs {
        PortalViews views();

        Collection<TravelMessage.TravelBegin> arms();

        PortalViews.Crossing crossing();

        /** Whether a seamless travel view owns the screen, which suppresses streamed mesh views. */
        boolean travelViewActive();
    }

    /** Stand-in until the client wiring installs its inputs: a private view set that only sees mirrors. */
    private static final class MirrorInputs implements FrameInputs {
        private final PortalViews views = new PortalViews(new ResidentLevels(message -> {
        }, 0L));

        @Override
        public PortalViews views() {
            return views;
        }

        @Override
        public Collection<TravelMessage.TravelBegin> arms() {
            return List.of();
        }

        @Override
        public PortalViews.Crossing crossing() {
            return null;
        }

        @Override
        public boolean travelViewActive() {
            return false;
        }
    }

    public static final class StreamedScope implements AutoCloseable {
        private final boolean stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        private final int function = GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC);
        private final int reference;
        private final int valueMask = GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK);
        private final int writeMask = GL11C.glGetInteger(GL11C.GL_STENCIL_WRITEMASK);
        private final int fail = GL11C.glGetInteger(GL11C.GL_STENCIL_FAIL);
        private final int depthFail = GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_FAIL);
        private final int depthPass = GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_PASS);
        private final int frontFace = GL11C.glGetInteger(GL11C.GL_FRONT_FACE);
        private final boolean clipping = PortalStencil.clipping();

        private StreamedScope(int reference) {
            this.reference = reference;
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
            PortalStencil.clipping(clipping);
        }
    }

    private record Candidate(PortalView view, double distance) {
        private Candidate {
            Objects.requireNonNull(view, "view");
        }
    }
}
