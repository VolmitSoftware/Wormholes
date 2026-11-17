package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.client.ClientMeshWorld;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesClientConfig;
import art.arcane.wormholes.modded.client.render.stencil.FrameCamera;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalViewSelection;
import art.arcane.wormholes.portal.ApertureKind;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streamed-mesh portal path ({@code MESH_RENDER}, {@code LOCAL_MESH}, {@code PLATES}): meshes server-sent
 * {@link PortalScene} sections into GPU meshes, renders each visible portal into its own target with its own camera
 * and projection, and composites it through the aperture: first into a full-screen layer that keeps each aperture's
 * depth, then the layer into the main target. Nested portals render into deeper scratch targets and composite into
 * their parent's target.
 */
public final class ClientPortalRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_GPU_BYTES = 128L * 1024L * 1024L;
    static final long RETRY_NANOS = 5_000_000_000L;
    private static final long BUILD_DISPATCH_NANOS = 2_000_000L;
    private static final List<RenderType> OPAQUE_LAYERS = List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout());
    private static final List<RenderType> TRANSLUCENT_LAYERS = List.of(RenderType.translucent(), RenderType.tripwire());
    private static final Comparator<Section> FARTHER_SECTION_FIRST = Comparator.comparingDouble((Section section) -> section.sortDistance).reversed();
    private static final ClientPortalRenderer INSTANCE = new ClientPortalRenderer();
    private final Int2ObjectOpenHashMap<Portal> portals = new Int2ObjectOpenHashMap<>();
    private final ExecutorService compiler = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Wormholes portal mesher").factory());
    private final ConcurrentLinkedQueue<MeshCompletion> meshCompletions = new ConcurrentLinkedQueue<>();
    private final List<Portal> visible = new ArrayList<>();
    private final List<Portal> buildDemand = new ArrayList<>();
    private int frameWidth;
    private int frameHeight;
    private PortalViewCamera camera;
    private PortalViewCamera rootCamera;
    private FrameCamera rootFrame;
    private ClientLevel frameLevel;
    private final PortalRenderTargets targets = new PortalRenderTargets();
    private TextureTarget portalLayer;
    private PortalGpuMesh layerMesh;
    private final Matrix4f frameProjection = new Matrix4f();
    private int pendingBuilds;
    private int residentBuilds;
    private int lastBuildPortal;
    private long buildBudgetNanos = BUILD_DISPATCH_NANOS;
    private long retainedSequence;
    private long gpuBytes;
    private long preparedFrame;
    private long terrainFrames;
    private long terrainDraws;
    private long frameTerrainDraws;
    private long lastTerrainFrame;
    private long lastAffineRevision = -1L;
    private final LinkedHashMap<RetainedMeshKey, Section> retainedMeshes = new LinkedHashMap<>();
    private final NavigableMap<Long, RetainedMeshKey> retainedMeshOrder = new TreeMap<>();
    private final IdentityHashMap<Object, Integer> retainedProofReferences = new IdentityHashMap<>();
    private long retainedProofBytes;
    private BakedModel models;
    private boolean ambientOcclusion;
    private int recursionCeiling = PortalRenderTargets.DEPTHS;

    private ClientPortalRenderer() {
    }

    public static ClientPortalRenderer instance() {
        return INSTANCE;
    }

    /** Whether streamed views are shaded by the active shader pipeline. They are not on 1.20.1: a shader pack composites them unshaded after its final pass. */
    public boolean streamedShadingActive() {
        return false;
    }

    static long retainedMeshBudget(long remainingNanos, int pendingBuilds) {
        return pendingBuilds < 2 ? remainingNanos / 2 : remainingNanos;
    }

    public String debugLine() {
        int sections = 0;
        int failures = 0;
        for (Portal portal : portals.values()) {
            sections += portal.sections.size();
            failures += portal.failure == null ? 0 : 1;
        }
        return "gpu=" + portals.size() + "/" + visible.size() + " mesh=" + sections + " pending=" + pendingBuilds
            + " gpuKiB=" + (gpuBytes >> 10) + " targetKiB=" + (targets.bytes() >> 10) + " unavailable=" + failures;
    }

    /** Streamed views drawn last frame. */
    public int drawnViews() {
        return visible.size();
    }

    public NativeProof nativeProof() {
        int sections = 0;
        int unavailable = 0;
        for (Portal portal : portals.values()) {
            sections += portal.sections.size();
            if (!portal.active || portal.failure != null) {
                unavailable++;
            }
        }
        return new NativeProof(preparedFrame, terrainFrames, terrainDraws, lastTerrainFrame, visible.size(), sections, unavailable,
            lastAffineRevision);
    }

    public record NativeProof(long preparedFrame, long terrainFrames, long terrainDraws, long lastTerrainFrame, int visiblePortals,
                              int meshSections, int unavailablePortals, long lastAffineRevision) {
    }

    /** The aperture shape of a streamed portal, or {@code null}. */
    public ShapeMesh apertureShape(int portalKey) {
        Portal portal = portals.get(portalKey);
        return portal == null || portal.apertureMesh == null ? null : portal.apertureShape;
    }

    /** Whether the streamed portal can render now. */
    public boolean available(int portalKey) {
        Portal portal = portals.get(portalKey);
        if (portal == null || !sourceCurrent(portal.scene, activeLevel())) {
            return false;
        }
        retry(portal, System.nanoTime());
        return portal.active;
    }

    /** Whether a streamed view covers the vanilla end portal surface at {@code position}. */
    public boolean coversEndPortalSurface(BlockPos position) {
        for (Portal portal : portals.values()) {
            if (!sourceCurrent(portal.scene, activeLevel())) {
                continue;
            }
            ApertureDescriptor geometry = portal.scene.geometry();
            if (portal.active && portal.rendered && geometry.parentPortalKey() == 0
                && geometry.kind() == ApertureKind.VANILLA_REPLACEMENT
                && geometry.facingDirection().y() != 0
                && geometry.containsCell(position.getX(), position.getY(), position.getZ())) {
                return true;
            }
        }
        return false;
    }

    public void featureFailed(int portalKey, Throwable failure) {
        Portal portal = portals.get(portalKey);
        if (portal != null) {
            fail(portal, Failure.FEATURES, failure);
        }
    }

    public void featuresReady(int portalKey) {
        Portal portal = portals.get(portalKey);
        if (portal != null) {
            recovered(portal, Failure.FEATURES);
        }
    }

    /** Receives the main view projection for this frame. */
    public void captureProjection(Matrix4f projection) {
        if (!StreamedViewHost.nested()) {
            frameProjection.set(projection);
        }
    }

    public void replaceScene(int portalKey, PortalScene scene) {
        remove(portalKey);
        portals.put(portalKey, new Portal(portalKey, scene));
    }

    public void refreshScene(int portalKey, PortalScene scene, boolean resetHistory) {
        Portal portal = portals.get(portalKey);
        if (portal == null) {
            replaceScene(portalKey, scene);
            return;
        }
        boolean changed = !portal.geometry.sameSurface(scene.geometry());
        portal.scene = scene;
        portal.restoreSequence = 0L;
        if (!changed) {
            if (!apertureSettingsCurrent(portal)) {
                closeAperture(portal);
            }
            return;
        }
        portal.updateGeometry();
        closeAperture(portal);
        portal.parentClip = null;
    }

    public void remove(int portalKey) {
        Portal removed = portals.remove(portalKey);
        if (removed != null) {
            removed.active = false;
            release(removed);
        }
    }

    public void clear() {
        discardFrame();
        clearRetainedMeshes();
        releaseFrameTargets();
        for (Portal portal : portals.values()) {
            portal.active = false;
            release(portal);
        }
        portals.clear();
        clearRetainedMeshes();
        finishBuilds();
        visible.clear();
        buildDemand.clear();
        residentBuilds = 0;
        lastBuildPortal = 0;
        buildBudgetNanos = BUILD_DISPATCH_NANOS;
    }

    /** Marks a streamed section changed or removed. */
    public void invalidate(int portalKey, long sectionKey, boolean changed) {
        Portal portal = portals.get(portalKey);
        if (portal != null) {
            if (changed && portal.failure == Failure.CONTENT) {
                portal.active = true;
            }
            portal.evicted.remove(sectionKey);
            Section displayed = portal.sections.get(sectionKey);
            if (displayed != null) {
                displayed.identity = null;
            }
            if (changed) {
                portal.dirty.addAndMoveToFirst(sectionKey);
            } else {
                portal.dirty.add(sectionKey);
            }
        }
    }

    /** The game's resources finished reloading; the shaders come back through {@link PortalShaders}. */
    public void resourcesReady() {
        RenderSystem.assertOnRenderThread();
        resourceReload();
    }

    public void resourceReload() {
        invalidateMeshes();
    }

    public void close() {
        RenderSystem.assertOnRenderThread();
        clear();
    }

    /** Uploads finished background mesh builds. */
    public void finishBuilds() {
        MeshCompletion completion;
        while ((completion = meshCompletions.poll()) != null) {
            finish(completion.portal(), completion.key(), completion.revision(), completion.generation(), completion.identity(),
                completion.mesh(), completion.failure());
        }
    }

    /** Drops per-frame state when a frame is skipped. */
    public void discardFrame() {
        visible.clear();
        buildDemand.clear();
        portalLayer = null;
        frameLevel = null;
        for (Portal portal : portals.values()) {
            portal.rendered = false;
            portal.rendering = false;
            portal.target = null;
        }
    }

    private static ClientLevel activeLevel() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.level;
    }

    static boolean sourceCurrent(PortalScene scene, ClientLevel level) {
        ClientLevel owner = scene.sourceLevel();
        return !scene.sourceExpired() && (owner == null || owner == level);
    }

    private static boolean streamedContext() {
        WormholesClient client = WormholesClient.instance();
        return !StreamedViewHost.nested() && (client == null
            || client.viewsAttachedTo(activeLevel()) && !client.seamlessTravel().view().active());
    }

    private boolean frameCurrent() {
        if (StreamedViewHost.nested()) {
            return false;
        }
        ClientLevel level = activeLevel();
        if (frameLevel != level || !streamedContext()) {
            discardFrame();
            return false;
        }
        for (Portal portal : visible) {
            if (!sourceCurrent(portal.scene, level)) {
                discardFrame();
                return false;
            }
        }
        return true;
    }

    /** Selects visible streamed views and renders them into their targets and the aperture layer. */
    public void prepare(FrameCamera frame) {
        if (StreamedViewHost.shadowPass()) {
            return;
        }
        if (!streamedContext()) {
            if (!StreamedViewHost.nested()) {
                discardFrame();
            }
            return;
        }
        preparedFrame++;
        frameTerrainDraws = 0L;
        frameLevel = activeLevel();
        finishBuilds();
        frameProjection.set(frame.projection());
        rootFrame = frame;
        camera = PortalViewCamera.of(frame);
        rootCamera = camera;
        visible.clear();
        buildDemand.clear();
        try {
            retryUnavailable(System.nanoTime());
            if (!hasActivePortals() || !PortalShaders.streamedReady()) {
                releaseFrameTargets();
                return;
            }
            try (PortalGlScope scope = new PortalGlScope()) {
                prepareFrame(scope);
            }
        } catch (RuntimeException failure) {
            failFrame(failure);
        } finally {
            buildDemand.clear();
            camera = rootCamera;
        }
    }

    private void invalidateMeshes() {
        clearRetainedMeshes();
        buildDemand.clear();
        releaseFrameTargets();
        for (Portal portal : portals.values()) {
            portal.generation++;
            portal.evicted.clear();
            release(portal);
            for (LongIterator iterator = portal.scene.sectionKeys().iterator(); iterator.hasNext();) {
                portal.dirty.add(iterator.nextLong());
            }
        }
        clearRetainedMeshes();
        models = null;
    }

    private boolean hasActivePortals() {
        for (Portal portal : portals.values()) {
            if (portal.active && sourceCurrent(portal.scene, activeLevel())) {
                return true;
            }
        }
        return false;
    }

    private void prepareFrame(PortalGlScope scope) {
        buildBudgetNanos = BUILD_DISPATCH_NANOS;
        Minecraft minecraft = Minecraft.getInstance();
        BakedModel currentModels = minecraft.getModelManager().getMissingModel();
        boolean currentAmbientOcclusion = Minecraft.useAmbientOcclusion();
        if (models != currentModels || currentAmbientOcclusion != ambientOcclusion) {
            invalidateMeshes();
            models = currentModels;
            ambientOcclusion = currentAmbientOcclusion;
        }
        RenderTarget main = ClientRenderAccess.mainTarget(minecraft);
        frameWidth = main.width;
        frameHeight = main.height;
        RenderDimensions dimensions = new RenderDimensions(main.width, main.height, 0);
        portalLayer = null;
        if (layerMesh == null) {
            layerMesh = fullscreenMesh();
        }
        for (Portal portal : portals.values()) {
            portal.rendered = false;
            portal.rendering = false;
            portal.target = null;
        }
        for (Portal portal : portals.values()) {
            if (sourceCurrent(portal.scene, minecraft.level) && !portal.scene.fullWorld() && portal.scene.geometry().parentPortalKey() == 0
                && !StreamedViewHost.claims(portal.scene.geometry()) && renderTree(portal, new Matrix4d(), null, dimensions)) {
                if (portalLayer == null) {
                    portalLayer = targets.layer(main.width, main.height);
                    portalLayer.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                    portalLayer.clear(Minecraft.ON_OSX);
                }
                visible.add(portal);
                portalLayer.bindWrite(true);
                composite(portal, null, frameProjection);
            }
        }
        dispatchBuilds();
        for (Portal portal : portals.values()) {
            if (!portal.rendered) {
                releaseTarget(portal);
            }
        }
        scope.rebindOuter();
        this.camera = rootCamera;
    }

    /** Whether {@code parentKey} has streamed children to draw at {@code depth}. */
    public boolean nestedViews(int parentKey, ClientLevel home, int depth, int ceiling) {
        Portal parent = portals.get(parentKey);
        if (parent == null || !sourceCurrent(parent.scene, home) || depth >= ceiling || !PortalShaders.streamedReady()) {
            return false;
        }
        for (Portal portal : portals.values()) {
            if (streamedChild(portal.scene.geometry(), parentKey, depth, ceiling, StreamedViewHost.claimsChild(portal.key))
                && portal.active && sourceCurrent(portal.scene, home) && portal.scene.environment() != null) {
                return true;
            }
        }
        return false;
    }

    static boolean streamedChild(ApertureDescriptor geometry, int parentKey, int depth, int ceiling, boolean nativeChild) {
        return parentKey != 0 && geometry.parentPortalKey() == parentKey && depth < Math.min(ceiling, PortalRenderTargets.DEPTHS)
            && !nativeChild;
    }

    /** Renders streamed children of a stencil view from inside that view's layer, compositing into the bound target. */
    public void renderNested(int parentKey, ClientLevel home, FrameCamera parentCamera, Matrix4fc projection, int depth, int ceiling) {
        if (!nestedViews(parentKey, home, depth, ceiling) || frameLevel != home) {
            return;
        }
        PortalViewCamera previousCamera = camera;
        PortalViewCamera previousRoot = rootCamera;
        FrameCamera previousFrame = rootFrame;
        Matrix4f previousProjection = new Matrix4f(frameProjection);
        int previousCeiling = recursionCeiling;
        List<Portal> previousDemand = List.copyOf(buildDemand);
        try (PortalGlScope scope = new PortalGlScope()) {
            rootFrame = parentCamera;
            rootCamera = PortalViewCamera.of(parentCamera);
            camera = rootCamera;
            frameProjection.set(projection);
            recursionCeiling = Math.min(ceiling, PortalRenderTargets.DEPTHS);
            buildDemand.clear();
            RenderTarget main = ClientRenderAccess.mainTarget(Minecraft.getInstance());
            renderNestedContent(parentKey, new RenderDimensions(main.width, main.height, depth), scope);
            dispatchBuilds();
        } finally {
            buildDemand.clear();
            buildDemand.addAll(previousDemand);
            recursionCeiling = previousCeiling;
            frameProjection.set(previousProjection);
            rootCamera = previousRoot;
            rootFrame = previousFrame;
            camera = previousCamera;
        }
    }

    private void renderNestedContent(int parentKey, RenderDimensions dimensions, PortalGlScope scope) {
        for (Portal portal : portals.values()) {
            if (!streamedChild(portal.scene.geometry(), parentKey, dimensions.depth(), recursionCeiling,
                StreamedViewHost.claimsChild(portal.key))) {
                continue;
            }
            resetBranch(portal, dimensions.depth());
            boolean rendered = renderTree(portal, new Matrix4d(), null, dimensions);
            if (rendered) {
                scope.rebindOuter();
                composite(portal, null, frameProjection);
            }
            camera = rootCamera;
        }
    }

    private void resetBranch(Portal portal, int depth) {
        portal.rendered = false;
        portal.rendering = false;
        if (depth + 1 >= PortalRenderTargets.DEPTHS) {
            return;
        }
        for (Portal child : portals.values()) {
            if (child.scene.geometry().parentPortalKey() == portal.key) {
                resetBranch(child, depth + 1);
            }
        }
    }

    /** Whether any view must be drawn by the shader pipeline. Never on 1.20.1; see {@link #compositeAfterShaders()}. */
    public boolean shadedViews() {
        return false;
    }

    public void renderShadedViews() {
    }

    static int branchCeiling(int inherited, int depth, int requested) {
        return Math.min(inherited, depth + Math.max(1, requested));
    }

    /** Composites prepared streamed views into the bound (main) target through their apertures. */
    public void composite(PoseStack poseStack, Matrix4f projection) {
        if (StreamedViewHost.shadowPass() || StreamedViewHost.shaderPack() || !frameCurrent()) {
            return;
        }
        compositeLayer();
    }

    /** Composites the views into the main target once a shader pack's final pass has written it. */
    public void compositeAfterShaders() {
        if (StreamedViewHost.nested() || !StreamedViewHost.shaderPack()) {
            return;
        }
        if (!frameCurrent() || layerMesh == null || visible.isEmpty() || portalLayer == null) {
            return;
        }
        try (PortalGlScope scope = new PortalGlScope()) {
            ClientRenderAccess.mainTarget(Minecraft.getInstance()).bindWrite(true);
            compositeLayer();
        }
    }

    private void compositeLayer() {
        try {
            if (layerMesh == null || visible.isEmpty() || portalLayer == null) {
                return;
            }
            ShaderInstance shader = PortalShaders.get(PortalShaders.LAYER);
            if (shader == null) {
                return;
            }
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11C.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.disableCull();
            shader.setSampler("Sampler0", portalLayer.getColorTextureId());
            shader.setSampler("Sampler1", portalLayer.getDepthTextureId());
            if (shader.SCREEN_SIZE != null) {
                shader.SCREEN_SIZE.set((float) portalLayer.width, (float) portalLayer.height);
            }
            shader.apply();
            layerMesh.drawApplied();
            shader.clear();
            RenderSystem.depthFunc(GL11C.GL_LEQUAL);
            RenderSystem.enableCull();
            if (frameTerrainDraws > 0L && lastTerrainFrame != preparedFrame) {
                boolean terrainComposited = false;
                for (Portal portal : visible) {
                    if (portal.lastCompositeFrame != preparedFrame) {
                        continue;
                    }
                    terrainComposited = true;
                    if (portal.affine != null) {
                        lastAffineRevision = portal.affine.sample().sampleRevision();
                    }
                }
                if (terrainComposited) {
                    terrainFrames++;
                    lastTerrainFrame = preparedFrame;
                }
            }
        } catch (RuntimeException failure) {
            failFrame(failure);
        } finally {
            camera = rootCamera;
        }
    }

    private void failFrame(RuntimeException failure) {
        for (Portal portal : portals.values()) {
            fail(portal, Failure.FRAME, failure);
        }
        visible.clear();
    }

    private boolean renderTree(Portal portal, Matrix4d toRoot, PortalViewport parentViewport, RenderDimensions dimensions) {
        try {
            return renderTreeContent(portal, toRoot, parentViewport, dimensions);
        } catch (RuntimeException failure) {
            fail(portal, Failure.FRAME, failure);
            return false;
        } finally {
            portal.rendering = false;
        }
    }

    private boolean renderTreeContent(Portal portal, Matrix4d toRoot, PortalViewport parentViewport, RenderDimensions dimensions) {
        if (!sourceCurrent(portal.scene, frameLevel) || !portal.active || portal.scene.environment() == null || portal.rendering || portal.rendered
            || dimensions.depth() >= Math.min(recursionCeiling, PortalRenderTargets.DEPTHS)) {
            return false;
        }
        if (!portal.toRoot.equals(toRoot)) {
            portal.parentClip = null;
        }
        if (toRoot.equals(new Matrix4d()) && StreamedViewHost.nested()) {
            portal.updateAffine();
            if (portal.affine == null && !StreamedViewHost.visible(PortalSurface.of(portal.geometry), rootFrame)) {
                return false;
            }
            PortalViewport enclosing = viewport(StreamedViewHost.window(rootFrame), dimensions.width(), dimensions.height());
            parentViewport = parentViewport == null ? enclosing : enclosing == null ? null : parentViewport.intersect(enclosing);
            if (parentViewport == null) {
                return false;
            }
        }
        StreamDemand demand = selectPortal(portal, rootCamera, toRoot, frameProjection, parentViewport, dimensions);
        if (demand == null) {
            return false;
        }
        portal.toRoot.set(toRoot);
        portal.contentToRoot.set(demand.contentToRoot());
        portal.camera = demand.camera();
        portal.contentCamera = demand.contentCamera();
        portal.viewport = demand.viewport();
        camera = portal.contentCamera;
        portal.cullFrustum = portal.viewport.frustum(camera, frameProjection, dimensions.width(), dimensions.height());
        portal.rendering = true;
        renderPortal(portal, dimensions);
        portal.rendering = false;
        portal.rendered = true;
        recovered(portal, Failure.FRAME);
        return true;
    }

    static Matrix4d contentSpace(Matrix4d toRoot, ApertureDescriptor geometry, float scale) {
        Matrix4d content = new Matrix4d(toRoot);
        if (scale == 1.0F) {
            return content;
        }
        Vec3d center = scaleCenter(geometry);
        Frame frame = geometry.frame();
        return content.mul(PortalProjection.matrix(Similarity.between(frame, center, frame, center, scale)));
    }

    static Vec3d scaleCenter(ApertureDescriptor geometry) {
        Frame canonical = Frame.canonical(geometry.facingDirection());
        double[] center = {geometry.originX(), geometry.originY(), geometry.originZ()};
        center[canonical.getRight().axisIndex()] += geometry.apertureWidth() / 2.0D;
        center[canonical.getUp().axisIndex()] += geometry.apertureHeight() / 2.0D;
        center[geometry.facingDirection().axisIndex()] = geometry.planeCoordinate();
        return new Vec3d(center[0], center[1], center[2]);
    }

    private static Matrix4d contentSpace(Portal portal, Matrix4d toRoot) {
        if (portal.affine != null) {
            return affineContentSpace(toRoot, portal.affine.sample(), portal.scene.environment());
        }
        return contentSpace(toRoot, portal.scene.geometry(), portal.scene.environment().scale());
    }

    static Matrix4d affineContentSpace(Matrix4d toRoot, ViewStreamMessage.PortalAffine sample, EnvironmentState environment) {
        return new Matrix4d(toRoot).mul(PortalProjection.matrix(sample.sourceToDestination()).invert())
            .mul(PortalProjection.destinationToSource(environment.transform()).invert());
    }

    /**
     * Draws {@code portal}'s aperture into the bound target, sampling the portal's own view. {@code parent} is the view the
     * aperture is seen through (its plane clips the child), or {@code null} for a root view.
     */
    private void composite(Portal portal, Portal parent, Matrix4fc projection) {
        if (portal.target == null || portal.apertureMesh == null) {
            return;
        }
        camera = portal.camera;
        boolean shaped = portal.affine != null || portal.aperture.hasShape();
        ShaderInstance shader = PortalShaders.get(shaped ? PortalShaders.COMPOSITE_SHAPE : PortalShaders.COMPOSITE);
        if (shader == null) {
            return;
        }
        Matrix4f modelView = portal.affine == null ? apertureTransform(portal.scene.geometry()) : affineApertureView(portal);
        Vector4f clip = parent == null ? new Vector4f(0.0F) : parentClip(portal, parent);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11C.GL_LEQUAL);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        shader.MODEL_VIEW_MATRIX.set(modelView);
        shader.PROJECTION_MATRIX.set(new Matrix4f(projection));
        shader.safeGetUniform("ClipPlane").set(clip.x, clip.y, clip.z, clip.w);
        shader.safeGetUniform("TargetSize").set((float) portal.target.width, (float) portal.target.height, 0.0F, 0.0F);
        shader.setSampler("Sampler0", portal.target.getColorTextureId());
        shader.apply();
        portal.apertureMesh.drawApplied();
        shader.clear();
        if (portal.lastDrawFrame == preparedFrame) {
            portal.lastCompositeFrame = preparedFrame;
        }
        ShaderInstance feather = PortalShaders.get(PortalShaders.FEATHER);
        if (shaped && portal.apertureFeather > 0.0F && feather != null) {
            EnvironmentState.Color color = portal.scene.environment().fog().color();
            feather.MODEL_VIEW_MATRIX.set(modelView);
            feather.PROJECTION_MATRIX.set(new Matrix4f(projection));
            feather.safeGetUniform("ClipPlane").set(clip.x, clip.y, clip.z, clip.w);
            feather.safeGetUniform("FeatherColor").set(color.red(), color.green(), color.blue(), 1.0F);
            feather.safeGetUniform("FeatherShape").set(portal.apertureFeather, 0.0F, 0.0F, 0.0F);
            feather.apply();
            portal.apertureMesh.drawApplied();
            feather.clear();
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static Vector4f parentClip(Portal portal, Portal parent) {
        if (portal.parentClip != null && portal.parentClipGeometry == parent.scene.geometry()
            && portal.parentClipAffine == parent.scene.affine() && portal.parentClipToRoot.equals(parent.toRoot)) {
            return portal.parentClip;
        }
        portal.parentClipGeometry = parent.scene.geometry();
        portal.parentClipAffine = parent.scene.affine();
        portal.parentClipToRoot.set(parent.toRoot);
        AperturePolygon.Plane plane = parent.sourcePlane();
        float side = parent.scene.geometry().frontSide() ? 1 : -1;
        Matrix4d childToParent = new Matrix4d(parent.toRoot).invert().mul(portal.toRoot);
        if (portal.affine != null) {
            childToParent.mul(portal.affine.meshToSource());
        }
        Vector4d transformed = childToParent.transpose().transform(new Vector4d(plane.x(), plane.y(), plane.z(), plane.offset()));
        double offset = portal.affine == null ? transformed.w + transformed.x * portal.scene.geometry().originX()
            + transformed.y * portal.scene.geometry().originY() + transformed.z * portal.scene.geometry().originZ() : transformed.w;
        portal.parentClip = new Vector4f(side * (float) transformed.x, side * (float) transformed.y, side * (float) transformed.z,
            side * (float) offset);
        return portal.parentClip;
    }

    private void maintain(Portal portal) {
        int cameraSectionX = camera.blockPos().getX() >> 4;
        int cameraSectionY = camera.blockPos().getY() >> 4;
        int cameraSectionZ = camera.blockPos().getZ() >> 4;
        long cameraSection = SectionPos.asLong(cameraSectionX, cameraSectionY, cameraSectionZ);
        if (portal.cameraSection != cameraSection) {
            portal.evicted.clear();
            portal.cameraSection = cameraSection;
        }
        for (LongIterator iterator = portal.scene.sectionKeys().iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            if (!portal.sections.containsKey(key) && !portal.building.contains(key) && !portal.evicted.contains(key)) {
                portal.dirty.add(key);
            }
        }
        LongIterator existing = portal.sections.keySet().iterator();
        while (existing.hasNext()) {
            long key = existing.nextLong();
            if (portal.scene.revision(key) < 0 && !portal.scene.refreshing(key)) {
                closeSection(portal.sections.get(key));
                existing.remove();
                portal.orderDirty = true;
            }
        }
        if (selectNextSection(portal)) {
            buildDemand.add(portal);
        }
    }

    private boolean selectNextSection(Portal portal) {
        long resident = 0L;
        long initial = 0L;
        double distance = Double.POSITIVE_INFINITY;
        boolean foundResident = false;
        boolean foundInitial = false;
        for (LongIterator iterator = portal.dirty.iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            if (portal.scene.revision(key) < 0) {
                if (!portal.scene.refreshing(key)) {
                    iterator.remove();
                }
            } else if (!portal.building.contains(key) && visibleSection(portal, key)) {
                if (portal.sections.containsKey(key)) {
                    if (!foundResident) {
                        resident = key;
                        foundResident = true;
                    }
                } else {
                    double candidate = distance(key);
                    if (candidate < distance) {
                        initial = key;
                        distance = candidate;
                        foundInitial = true;
                    }
                }
            }
        }
        portal.hasResidentBuild = foundResident;
        portal.residentSection = resident;
        portal.hasInitialBuild = foundInitial;
        portal.initialSection = initial;
        if (foundResident && (!foundInitial || residentBuilds < 3)) {
            portal.nextSection = resident;
            return true;
        }
        if (!foundInitial) {
            return false;
        }
        portal.nextSection = initial;
        return true;
    }

    private void dispatchBuilds() {
        long started = System.nanoTime();
        try {
            dispatchBuilds(started + buildBudgetNanos);
        } finally {
            spendBuildBudget(started);
        }
    }

    private void dispatchBuilds(long deadline) {
        PortalViewCamera previous = camera;
        try {
            int submissions = 0;
            while (System.nanoTime() < deadline) {
                boolean canCompile = pendingBuilds < 2 && submissions < 2;
                boolean resident = residentBuilds < 3;
                Portal portal = nextBuildPortal(resident, canCompile, deadline);
                if (portal == null) {
                    resident = !resident;
                    portal = nextBuildPortal(resident, canCompile, deadline);
                }
                if (portal == null) {
                    return;
                }
                camera = portal.contentCamera;
                portal.nextSection = resident ? portal.residentSection : portal.initialSection;
                int before = pendingBuilds;
                try {
                    schedule(portal, portal.nextSection);
                    if (portal.nextSection == portal.contentFailureSection) {
                        recovered(portal, Failure.CONTENT);
                    }
                } catch (RuntimeException failure) {
                    portal.contentFailureSection = portal.nextSection;
                    fail(portal, Failure.CONTENT, failure);
                    continue;
                }
                if (pendingBuilds != before) {
                    submissions++;
                }
                if (pendingBuilds < 2 && submissions < 2) {
                    selectNextSection(portal);
                }
            }
        } finally {
            camera = previous;
        }
    }

    private Portal nextBuildPortal(boolean resident, boolean canCompile, long deadline) {
        int start = 0;
        for (int index = 0; index < buildDemand.size(); index++) {
            if (buildDemand.get(index).key == lastBuildPortal) {
                start = index + 1;
                break;
            }
        }
        for (int checked = 0; checked < buildDemand.size(); checked++) {
            Portal portal = buildDemand.get((start + checked) % buildDemand.size());
            if (portal.active && (resident ? portal.hasResidentBuild : portal.hasInitialBuild)
                && (canCompile || selectCheapSection(portal, resident, deadline))) {
                return portal;
            }
        }
        return null;
    }

    private boolean selectCheapSection(Portal portal, boolean resident, long deadline) {
        for (LongIterator iterator = portal.dirty.iterator(); iterator.hasNext();) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            long key = iterator.nextLong();
            if (portal.scene.revision(key) < 0 || portal.building.contains(key)
                || portal.sections.containsKey(key) != resident || !visibleSection(portal, key)) {
                continue;
            }
            if (portal.scene.empty(key) || reusableMesh(portal, key)) {
                if (resident) {
                    portal.residentSection = key;
                } else {
                    portal.initialSection = key;
                }
                return true;
            }
        }
        return false;
    }

    private boolean reusableMesh(Portal portal, long key) {
        Section cached = cachedMesh(portal, key);
        return cached != null && portal.scene.matchesMeshIdentity(key, cached.identity);
    }

    private Section cachedMesh(Portal portal, long key) {
        if (retainedMeshes.isEmpty()) {
            return null;
        }
        PortalScene.MeshIdentity context = portal.scene.meshContext();
        return context == null ? null : retainedMeshes.get(new RetainedMeshKey(context, key));
    }

    private void schedule(Portal portal, long key) {
        if (portal.scene.empty(key)) {
            portal.dirty.remove(key);
            Section previous = portal.sections.put(key, new Section(key, portal.scene.revision(key)));
            if (previous != null) {
                closeSection(previous);
            }
            portal.orderDirty = true;
            residentBuilds = previous == null ? 0 : Math.min(3, residentBuilds + 1);
            lastBuildPortal = portal.key;
            return;
        }
        if (reuseMesh(portal, key)) {
            return;
        }
        BlockAndTintGetter world = portal.scene.world(key);
        PortalScene.MeshIdentity identity = world instanceof ClientMeshWorld snapshot
            ? snapshot.meshIdentity() : portal.scene.meshIdentity(key);
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        long revision = portal.scene.revision(key);
        int generation = portal.generation;
        portal.dirty.remove(key);
        portal.building.add(key);
        pendingBuilds++;
        residentBuilds = portal.sections.containsKey(key) ? Math.min(3, residentBuilds + 1) : 0;
        lastBuildPortal = portal.key;
        CompletableFuture.supplyAsync(() -> PortalSectionMesh.compile(key, world, blocks), compiler)
            .whenComplete((mesh, failure) -> meshCompletions.add(new MeshCompletion(portal, key, revision, generation, identity, mesh, failure)));
    }

    private void finish(Portal portal, long key, long revision, int generation, PortalScene.MeshIdentity identity, PortalSectionMesh mesh,
                        Throwable failure) {
        pendingBuilds--;
        portal.building.remove(key);
        long currentRevision = portal.scene.revision(key);
        Section displayed = portal.sections.get(key);
        if (!portal.active || portal.generation != generation || currentRevision < 0
            || displayed != null && displayed.revision > revision) {
            if (mesh != null) {
                mesh.close();
            }
            return;
        }
        if (currentRevision != revision) {
            portal.dirty.add(key);
        }
        if (failure != null) {
            if (portal.dirty.contains(key)) {
                if (mesh != null) {
                    mesh.close();
                }
                return;
            }
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            fail(portal, Failure.SECTION, failure);
            return;
        }
        try (mesh) {
            Section section = new Section(key, revision);
            Section previous = portal.sections.put(key, section);
            portal.orderDirty = true;
            if (previous != null) {
                closeSection(previous);
            }
            for (Map.Entry<RenderType, PortalGpuMesh> entry : mesh.upload().entrySet()) {
                section.layers.put(entry.getKey(), entry.getValue());
                gpuBytes += entry.getValue().bytes();
            }
            if (currentRevision == revision && !portal.dirty.contains(key)) {
                section.identity = identity;
            }
            trim();
            recovered(portal, Failure.SECTION);
        } catch (RuntimeException uploadFailure) {
            fail(portal, Failure.SECTION, uploadFailure);
        }
    }

    private void trim() {
        while (gpuBytes > MAX_GPU_BYTES) {
            if (!retainedMeshes.isEmpty()) {
                evictRetainedMesh();
                continue;
            }
            Portal owner = null;
            Section farthest = null;
            double distance = -1.0;
            for (Portal portal : portals.values()) {
                for (Section section : portal.sections.values()) {
                    PortalViewCamera current = camera;
                    camera = portal.contentCamera == null ? rootCamera : portal.contentCamera;
                    double candidate = camera == null ? 0.0 : distance(section.key);
                    camera = current;
                    if (candidate > distance) {
                        owner = portal;
                        farthest = section;
                        distance = candidate;
                    }
                }
            }
            if (owner == null) {
                return;
            }
            owner.sections.remove(farthest.key);
            owner.orderDirty = true;
            owner.evicted.add(farthest.key);
            closeSection(farthest);
        }
    }

    private void renderPortal(Portal portal, RenderDimensions dimensions) {
        prepareDestination(portal, dimensions);
        maintain(portal);
        Matrix4f clippedProjection = clippedProjection(portal);
        PortalGlScope.inner();
        bindDestination(portal);
        clearDestinationSky(portal);
        RenderSystem.setProjectionMatrix(clippedProjection, VertexSorting.DISTANCE_TO_ORIGIN);
        portal.environment.applyFog();
        boolean reflected = portal.contentToRoot.determinant3x3() < 0;
        for (RenderType layer : OPAQUE_LAYERS) {
            drawTerrain(portal, layer, reflected);
        }
        try (PortalLightmapScope lightmap = new PortalLightmapScope(portal.environment.lightmap())) {
            targets.features(dimensions.depth()).render(portal.scene, camera, portal.cullFrustum);
            recovered(portal, Failure.FEATURES);
        } catch (RuntimeException failure) {
            LOGGER.debug("Streamed portal {} features failed", portal.key, failure);
            portal.failure = portal.failure == null ? Failure.FEATURES : portal.failure;
        }
        drawNestedDestinations(portal, dimensions, clippedProjection);
        bindDestination(portal);
        RenderSystem.setProjectionMatrix(clippedProjection, VertexSorting.DISTANCE_TO_ORIGIN);
        portal.environment.applyFog();
        for (RenderType layer : TRANSLUCENT_LAYERS) {
            drawTerrain(portal, layer, reflected);
        }
        RenderSystem.disableScissor();
        camera = portal.camera;
    }

    private void bindDestination(Portal portal) {
        PortalViewport viewport = portal.viewport;
        portal.target.bindWrite(true);
        RenderSystem.enableScissor(viewport.x(), viewport.y(), viewport.width(), viewport.height());
    }

    private static PortalViewport viewport(PortalViewSelection.Window window, int width, int height) {
        int left = Math.clamp((int) Math.floor((window.minX() + 1.0D) * width * 0.5D), 0, width);
        int bottom = Math.clamp((int) Math.floor((window.minY() + 1.0D) * height * 0.5D), 0, height);
        int right = Math.clamp((int) Math.ceil((window.maxX() + 1.0D) * width * 0.5D), 0, width);
        int top = Math.clamp((int) Math.ceil((window.maxY() + 1.0D) * height * 0.5D), 0, height);
        return right > left && top > bottom ? new PortalViewport(left, bottom, right - left, top - bottom) : null;
    }

    private static StreamDemand selectPortal(Portal portal, PortalViewCamera root, Matrix4d toRoot, Matrix4f projection,
                                            PortalViewport parentViewport, RenderDimensions dimensions) {
        portal.updateAffine();
        Matrix4d contentToRoot = contentSpace(portal, toRoot);
        PortalViewCamera camera = root.transformed(toRoot, projection);
        PortalViewCamera contentCamera = contentToRoot.equals(toRoot) ? camera : root.transformed(contentToRoot, projection);
        Vec3d eye = new Vec3d(camera.pos().x, camera.pos().y, camera.pos().z);
        double eyeDistance = portal.sourcePlane().signedDistance(eye);
        if ((portal.geometry.frontSide() ? eyeDistance <= 0.0D : eyeDistance >= 0.0D) || !visiblePortal(portal, eye, camera)) {
            return null;
        }
        Matrix4d viewProjection = new Matrix4d(projection).mul(new Matrix4d(camera.viewRotation()))
            .translate(-camera.pos().x, -camera.pos().y, -camera.pos().z);
        PortalViewport viewport = PortalViewport.coverage(portal.renderAperture(), portal.affine == null ? viewProjection
            : new Matrix4d(viewProjection).mul(portal.affine.meshToSource()), dimensions.width(), dimensions.height());
        if (viewport != null && parentViewport != null) {
            viewport = viewport.intersect(parentViewport);
        }
        return viewport == null ? null : new StreamDemand(camera, contentCamera, contentToRoot, viewport);
    }

    private record StreamDemand(PortalViewCamera camera, PortalViewCamera contentCamera, Matrix4d contentToRoot, PortalViewport viewport) {
    }

    private RenderDimensions childDimensions(RenderDimensions parent) {
        return new RenderDimensions(parent.width(), parent.height(), parent.depth() + 1);
    }

    private PortalViewport childViewport(Portal portal, RenderDimensions dimensions) {
        RenderDimensions child = childDimensions(dimensions);
        return portal.viewport.rescale(dimensions.width(), dimensions.height(), child.width(), child.height());
    }

    private void prepareDestination(Portal portal, RenderDimensions dimensions) {
        portal.target = portal.scene.fullWorld() ? targets.travel(portal.key, dimensions.width(), dimensions.height())
            : targets.scratch(dimensions.depth(), dimensions.width(), dimensions.height());
        restoreRetainedMeshes(portal);
        if (!portal.apertureReady) {
            portal.apertureMesh = apertureMesh(portal);
            portal.apertureReady = true;
        }
        if (portal.environment == null) {
            portal.environment = new PortalEnvironmentRenderer();
        }
        portal.environment.prepare(portal.scene.environment(), camera);
        portal.drawSections.clear();
        for (Section section : orderedSections(portal)) {
            if (portal.cullFrustum.isVisible(section.bounds)) {
                portal.drawSections.add(section);
                for (RenderType layer : TRANSLUCENT_LAYERS) {
                    PortalGpuMesh translucent = section.layers.get(layer);
                    if (translucent != null) {
                        translucent.sort((float) (camera.pos().x - (SectionPos.x(section.key) << 4)),
                            (float) (camera.pos().y - (SectionPos.y(section.key) << 4)), (float) (camera.pos().z - (SectionPos.z(section.key) << 4)));
                    }
                }
            }
        }
    }

    private void clearDestinationSky(Portal portal) {
        PortalEnvironment.Fog fog = portal.environment.fog();
        RenderSystem.clearColor(fog.color().x, fog.color().y, fog.color().z, 1.0F);
        RenderSystem.clearDepth(1.0D);
        RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT | GL11C.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        if (portal.environment.skybox()) {
            RenderSystem.setProjectionMatrix(new Matrix4f(frameProjection), VertexSorting.DISTANCE_TO_ORIGIN);
            portal.environment.renderSky(new Matrix4f(frameProjection));
            RenderSystem.clear(GL11C.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        }
    }

    private Matrix4f clippedProjection(Portal portal) {
        if (portal.scene.fullWorld()) {
            return new Matrix4f(frameProjection);
        }
        return PortalProjection.clip(frameProjection, camera.viewRotation(), cameraPlane(portal), false);
    }

    private Vector4f cameraPlane(Portal portal) {
        if (portal.scene.fullWorld()) {
            return new Vector4f(0.0f);
        }
        AperturePolygon.Plane plane = portal.contentPlane();
        float side = portal.scene.geometry().frontSide() ? 1 : -1;
        return new Vector4f(side * (float) plane.x(), side * (float) plane.y(), side * (float) plane.z(),
            side * (float) plane.signedDistance(new Vec3d(camera.pos().x, camera.pos().y, camera.pos().z)));
    }

    /**
     * The portals seen through this one, each rendered as its own tree into the next depth's target and composited into
     * this view's target with this view's clipped projection.
     */
    private void drawNestedDestinations(Portal portal, RenderDimensions dimensions, Matrix4f clippedProjection) {
        int previousCeiling = recursionCeiling;
        recursionCeiling = branchCeiling(previousCeiling, dimensions.depth(), portal.scene.geometry().recursionDepth());
        try {
            Matrix4d childSpace = new Matrix4d(portal.contentToRoot)
                .mul(PortalProjection.destinationToSource(portal.scene.environment().transform()));
            for (Portal child : portals.values()) {
                if (child.scene.geometry().parentPortalKey() != portal.key) {
                    continue;
                }
                if (renderTree(child, childSpace, childViewport(portal, dimensions), childDimensions(dimensions))) {
                    bindDestination(portal);
                    composite(child, portal, clippedProjection);
                }
                camera = portal.contentCamera;
            }
        } finally {
            recursionCeiling = previousCeiling;
            camera = portal.contentCamera;
        }
    }

    private void drawTerrain(Portal portal, RenderType layer, boolean reflected) {
        boolean translucent = TRANSLUCENT_LAYERS.contains(layer);
        ShaderInstance shader = PortalShaders.get(translucent ? PortalShaders.BLOCK_TRANSLUCENT : PortalShaders.BLOCK);
        if (shader == null || portal.drawSections.isEmpty()) {
            return;
        }
        layer.setupRenderState();
        if (reflected) {
            GL11C.glFrontFace(GL11C.GL_CW);
        }
        try {
            Vector4f plane = cameraPlane(portal);
            shader.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
            shader.setSampler("Sampler2", portal.environment.lightmap());
            shader.MODEL_VIEW_MATRIX.set(camera.viewRotation());
            shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
            shader.COLOR_MODULATOR.set(1.0F, 1.0F, 1.0F, 1.0F);
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
            shader.safeGetUniform("ClipPlane").set(plane.x, plane.y, plane.z, plane.w);
            shader.safeGetUniform("AlphaCutout").set(layer == RenderType.cutoutMipped() ? 0.5F
                : layer == RenderType.cutout() || layer == RenderType.tripwire() ? 0.1F : 0.0F);
            shader.apply();
            for (Section section : portal.drawSections) {
                PortalGpuMesh mesh = section.layers.get(layer);
                if (mesh == null) {
                    continue;
                }
                shader.CHUNK_OFFSET.set((float) ((SectionPos.x(section.key) << 4) - camera.pos().x),
                    (float) ((SectionPos.y(section.key) << 4) - camera.pos().y), (float) ((SectionPos.z(section.key) << 4) - camera.pos().z));
                shader.CHUNK_OFFSET.upload();
                mesh.drawApplied();
                terrainDraws++;
                frameTerrainDraws++;
                portal.lastDrawFrame = preparedFrame;
            }
            shader.clear();
        } finally {
            if (reflected) {
                GL11C.glFrontFace(GL11C.GL_CCW);
            }
            layer.clearRenderState();
        }
    }

    private static PortalGpuMesh fullscreenMesh() {
        ByteBuffer vertices = MemoryUtil.memAlloc(4 * 12);
        try {
            vertices.putFloat(-1).putFloat(-1).putFloat(0);
            vertices.putFloat(1).putFloat(-1).putFloat(0);
            vertices.putFloat(1).putFloat(1).putFloat(0);
            vertices.putFloat(-1).putFloat(1).putFloat(0);
            vertices.flip();
            return PortalGpuMesh.quads(vertices, DefaultVertexFormat.POSITION, 4, null);
        } finally {
            MemoryUtil.memFree(vertices);
        }
    }

    private void releaseFrameTargets() {
        targets.close();
        portalLayer = null;
        if (layerMesh != null) {
            layerMesh.close();
            layerMesh = null;
        }
    }

    private List<Section> orderedSections(Portal portal) {
        if (portal.orderDirty || !camera.pos().equals(portal.sortPosition)) {
            portal.sortedSections.clear();
            for (Section section : portal.sections.values()) {
                if (!section.layers.isEmpty()) {
                    section.sortDistance = distance(section.key);
                    portal.sortedSections.add(section);
                }
            }
            portal.sortedSections.sort(FARTHER_SECTION_FIRST);
            portal.sortPosition = camera.pos();
            portal.orderDirty = false;
        }
        return portal.sortedSections;
    }

    private static PortalGpuMesh apertureMesh(Portal portal) {
        if (portal.affine != null) {
            ApertureDescriptor local = portal.affine.localGeometry();
            int subdivisions = PortalApertureMesh.subdivisions(requestedShapeSubdivisions(), local.apertureWidth(), local.apertureHeight());
            portal.apertureSubdivisions = subdivisions;
            portal.apertureFeather = edgeFeather();
            portal.apertureShape = portal.affine.localShape().mesh(subdivisions, local.apertureMask());
            return PortalApertureMesh.shaped(portal.apertureShape, portal.affine.localAperture(), local);
        }
        if (!portal.aperture.hasShape()) {
            return PortalApertureMesh.quads(portal.aperture, portal.geometry);
        }
        int requested = requestedShapeSubdivisions();
        int subdivisions = PortalApertureMesh.subdivisions(requested, portal.geometry.apertureWidth(), portal.geometry.apertureHeight());
        if (subdivisions < requested) {
            LOGGER.info("Shaped portal {} spans {}x{} cells and uses {} edge subdivisions instead of {} to stay within the mesh budget",
                portal.key, portal.geometry.apertureWidth(), portal.geometry.apertureHeight(), subdivisions, requested);
        }
        portal.apertureSubdivisions = subdivisions;
        portal.apertureFeather = edgeFeather();
        portal.apertureShape = portal.aperture.planeShape().mesh(subdivisions, PortalApertureMesh.renderMask(portal.geometry, portal.aperture.planeShape()));
        return PortalApertureMesh.shaped(portal.apertureShape, portal.aperture, portal.geometry);
    }

    private static boolean apertureSettingsCurrent(Portal portal) {
        if (!portal.apertureReady) {
            return true;
        }
        ApertureDescriptor geometry = portal.affine == null ? portal.geometry : portal.affine.localGeometry();
        if (portal.affine == null && !portal.aperture.hasShape()) {
            return true;
        }
        return portal.apertureSubdivisions == PortalApertureMesh.subdivisions(requestedShapeSubdivisions(),
            geometry.apertureWidth(), geometry.apertureHeight()) && portal.apertureFeather == edgeFeather();
    }

    private static void closeAperture(Portal portal) {
        if (portal.apertureMesh != null) {
            portal.apertureMesh.close();
            portal.apertureMesh = null;
        }
        portal.apertureShape = null;
        portal.apertureReady = false;
    }

    private static int requestedShapeSubdivisions() {
        WormholesClient client = WormholesClient.instance();
        return client == null ? WormholesClientConfig.DEFAULT_PORTAL_SHAPE_SUBDIVISIONS : client.config().portalShapeSubdivisions;
    }

    private static float edgeFeather() {
        WormholesClient client = WormholesClient.instance();
        return client == null ? 0.0f : (float) client.config().portalEdgeFeather;
    }

    private Matrix4f affineApertureView(Portal portal) {
        return new Matrix4f(camera.viewRotation()).translate((float) -camera.pos().x, (float) -camera.pos().y, (float) -camera.pos().z)
            .mul(new Matrix4f(portal.affine.meshToSource()));
    }

    private Matrix4f apertureTransform(ApertureDescriptor geometry) {
        return new Matrix4f(camera.viewRotation()).translate((float) (geometry.originX() - camera.pos().x),
            (float) (geometry.originY() - camera.pos().y), (float) (geometry.originZ() - camera.pos().z));
    }

    private double distance(long key) {
        double x = (SectionPos.x(key) << 4) + 8.0 - camera.pos().x;
        double y = (SectionPos.y(key) << 4) + 8.0 - camera.pos().y;
        double z = (SectionPos.z(key) << 4) + 8.0 - camera.pos().z;
        return x * x + y * y + z * z;
    }

    private static boolean visiblePortal(Portal portal, Vec3d eye, PortalViewCamera camera) {
        return camera.frustum() == null || Math.abs(portal.sourcePlane().signedDistance(eye)) < 0.2
            || camera.frustum().isVisible(portal.bounds);
    }

    private boolean visibleSection(Portal portal, long key) {
        int x = SectionPos.x(key) << 4;
        int y = SectionPos.y(key) << 4;
        int z = SectionPos.z(key) << 4;
        return portal.cullFrustum.isVisible(new AABB(x - 1, y - 1, z - 1, x + 17, y + 17, z + 17));
    }

    private void release(Portal portal) {
        for (Section section : portal.sections.values()) {
            if (!retainMesh(portal, section)) {
                closeSection(section);
            }
        }
        portal.sections.clear();
        portal.sortedSections.clear();
        portal.drawSections.clear();
        portal.orderDirty = true;
        releaseTarget(portal);
        if (portal.environment != null) {
            portal.environment.close();
            portal.environment = null;
        }
        closeAperture(portal);
    }

    private void fail(Portal portal, Failure kind, Throwable failure) {
        if (!portal.active) {
            return;
        }
        if (portal.failure != kind) {
            if (kind == Failure.CONTENT) {
                LOGGER.error("Native portal {} has invalid section content; waiting for updated content", portal.key, failure);
            } else {
                LOGGER.error("Native portal {} is unavailable after {} failure; retrying native rendering in five seconds", portal.key, kind, failure);
            }
        }
        portal.failure = kind;
        portal.active = false;
        portal.retryAt = System.nanoTime() + RETRY_NANOS;
        portal.generation++;
        release(portal);
    }

    void retryUnavailable(long now) {
        for (Portal portal : portals.values()) {
            retry(portal, now);
        }
    }

    private static void retry(Portal portal, long now) {
        if (!portal.active && portal.failure != null && portal.failure != Failure.CONTENT && now - portal.retryAt >= 0L) {
            portal.active = true;
            portal.evicted.clear();
        }
    }

    private static void recovered(Portal portal, Failure kind) {
        if (portal.active && portal.failure == kind) {
            portal.failure = null;
        }
    }

    private static void releaseTarget(Portal portal) {
        portal.target = null;
        portal.parentClip = null;
    }

    private boolean retainMesh(Portal portal, Section section) {
        if (section.identity == null || portal.failure != null || portal.dirty.contains(section.key)
            || portal.building.contains(section.key) || section.revision != portal.scene.revision(section.key)) {
            return false;
        }
        RetainedMeshKey key = new RetainedMeshKey(section.identity, section.key);
        Section previous = removeRetainedMesh(key);
        if (previous != null) {
            removeMeshProof(previous.identity);
            closeSection(previous);
        }
        section.identity.references((reference, bytes) -> {
            int references = retainedProofReferences.getOrDefault(reference, 0);
            if (references == 0) {
                retainedProofBytes += bytes;
            }
            retainedProofReferences.put(reference, references + 1);
        });
        section.retainedSequence = ++retainedSequence;
        retainedMeshes.put(key, section);
        retainedMeshOrder.put(section.retainedSequence, key);
        while (!retainedMeshes.isEmpty() && (retainedProofBytes > 32L * 1024 * 1024 || gpuBytes > MAX_GPU_BYTES)) {
            evictRetainedMesh();
        }
        return true;
    }

    private boolean reuseMesh(Portal portal, long sectionKey) {
        Section cached = cachedMesh(portal, sectionKey);
        if (cached == null) {
            return false;
        }
        boolean matches = portal.scene.matchesMeshIdentity(sectionKey, cached.identity);
        removeRetainedMesh(new RetainedMeshKey(cached.identity, sectionKey));
        if (!matches) {
            removeMeshProof(cached.identity);
            closeSection(cached);
            return false;
        }
        installRetainedMesh(portal, cached, portal.scene.revision(sectionKey));
        return true;
    }

    private void restoreRetainedMeshes(Portal portal) {
        if (buildBudgetNanos <= 0L || retainedMeshes.isEmpty() || !portal.active || portal.failure != null) {
            return;
        }
        PortalScene.MeshIdentity context = portal.scene.meshContext();
        if (context == null) {
            return;
        }
        long started = System.nanoTime();
        long slice = retainedMeshBudget(buildBudgetNanos, pendingBuilds);
        if (slice == 0L) {
            return;
        }
        long deadline = started + slice;
        try {
            while (System.nanoTime() < deadline) {
                Map.Entry<Long, RetainedMeshKey> candidate = retainedMeshOrder.higherEntry(portal.restoreSequence);
                if (candidate == null) {
                    portal.restoreSequence = 0L;
                    return;
                }
                Section cached = retainedMeshes.get(candidate.getValue());
                portal.restoreSequence = candidate.getKey();
                if (!restorableMesh(portal, context, candidate.getValue())) {
                    continue;
                }
                if (System.nanoTime() >= deadline) {
                    portal.restoreSequence = cached.retainedSequence - 1L;
                    return;
                }
                if (portal.scene.matchesMeshIdentity(cached.key, cached.identity)) {
                    removeRetainedMesh(candidate.getValue());
                    installRetainedMesh(portal, cached, portal.scene.revision(cached.key));
                }
            }
        } finally {
            spendBuildBudget(started);
        }
    }

    private static boolean restorableMesh(Portal portal, PortalScene.MeshIdentity context, RetainedMeshKey key) {
        return context.sameContext(key.identity) && !portal.sections.containsKey(key.section) && !portal.building.contains(key.section)
            && portal.scene.revision(key.section) >= 0L;
    }

    private void spendBuildBudget(long started) {
        buildBudgetNanos = Math.max(0L, buildBudgetNanos - (System.nanoTime() - started));
    }

    private void installRetainedMesh(Portal portal, Section cached, long revision) {
        removeMeshProof(cached.identity);
        cached.revision = revision;
        Section previous = portal.sections.put(cached.key, cached);
        if (previous != null) {
            closeSection(previous);
        }
        portal.dirty.remove(cached.key);
        portal.orderDirty = true;
        lastBuildPortal = portal.key;
    }

    private void removeMeshProof(PortalScene.MeshIdentity identity) {
        identity.references((reference, bytes) -> {
            int references = retainedProofReferences.get(reference);
            if (references == 1) {
                retainedProofReferences.remove(reference);
                retainedProofBytes -= bytes;
            } else {
                retainedProofReferences.put(reference, references - 1);
            }
        });
    }

    private void evictRetainedMesh() {
        Map.Entry<RetainedMeshKey, Section> entry = retainedMeshes.entrySet().iterator().next();
        removeRetainedMesh(entry.getKey());
        removeMeshProof(entry.getValue().identity);
        closeSection(entry.getValue());
    }

    private Section removeRetainedMesh(RetainedMeshKey key) {
        Section removed = retainedMeshes.remove(key);
        if (removed != null) {
            retainedMeshOrder.remove(removed.retainedSequence);
        }
        return removed;
    }

    private void clearRetainedMeshes() {
        while (!retainedMeshes.isEmpty()) {
            evictRetainedMesh();
        }
    }

    private record RetainedMeshKey(PortalScene.MeshIdentity identity, long section) {
        @Override
        public int hashCode() {
            return 31 * identity.contextHash() + Long.hashCode(section);
        }

        @Override
        public boolean equals(Object value) {
            return this == value || value instanceof RetainedMeshKey other && section == other.section
                && identity.sameContext(other.identity);
        }
    }

    private void closeSection(Section section) {
        for (PortalGpuMesh mesh : section.layers.values()) {
            gpuBytes -= mesh.bytes();
            mesh.close();
        }
    }

    private record MeshCompletion(Portal portal, long key, long revision, int generation, PortalScene.MeshIdentity identity,
                                  PortalSectionMesh mesh, Throwable failure) {
    }

    private record RenderDimensions(int width, int height, int depth) {
    }

    private enum Failure {
        CONTENT,
        FRAME,
        SECTION,
        FEATURES
    }

    private static final class Portal {
        private final int key;
        private PortalScene scene;
        private ApertureDescriptor geometry;
        private final Matrix4d toRoot = new Matrix4d();
        private final Matrix4d contentToRoot = new Matrix4d();
        private final List<Section> drawSections = new ArrayList<>();
        private final List<Section> sortedSections = new ArrayList<>();
        private net.minecraft.world.phys.Vec3 sortPosition;
        private boolean orderDirty = true;
        private PortalViewCamera camera;
        private PortalViewCamera contentCamera;
        private Frustum cullFrustum;
        private boolean rendered;
        private long lastDrawFrame = -1L;
        private long lastCompositeFrame = -1L;
        private boolean rendering;
        private Vector4f parentClip;
        private ApertureDescriptor parentClipGeometry;
        private ViewStreamMessage.PortalAffine parentClipAffine;
        private final Matrix4d parentClipToRoot = new Matrix4d();
        private PortalViewport viewport;
        private AperturePolygon aperture;
        private AffinePortalSurface affine;
        private AABB bounds;
        private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
        private final LongLinkedOpenHashSet dirty = new LongLinkedOpenHashSet();
        private long nextSection;
        private long residentSection;
        private long initialSection;
        private boolean hasResidentBuild;
        private boolean hasInitialBuild;
        private final LongOpenHashSet building = new LongOpenHashSet();
        private final LongOpenHashSet evicted = new LongOpenHashSet();
        private long cameraSection = Long.MIN_VALUE;
        private long restoreSequence;
        private boolean active = true;
        private Failure failure;
        private long contentFailureSection;
        private long retryAt;
        private int generation;
        private TextureTarget target;
        private PortalEnvironmentRenderer environment;
        private PortalGpuMesh apertureMesh;
        private boolean apertureReady;
        private ShapeMesh apertureShape;
        private int apertureSubdivisions;
        private float apertureFeather;

        private Portal(int key, PortalScene scene) {
            this.key = key;
            this.scene = scene;
            updateGeometry();
        }

        private AperturePolygon renderAperture() {
            return affine == null ? aperture : affine.localAperture();
        }

        private AperturePolygon.Plane sourcePlane() {
            return affine == null ? aperture.plane() : affine.plane();
        }

        private AperturePolygon.Plane contentPlane() {
            return affine == null ? aperture.plane() : AffinePortalSurface.transformedPlane(affine.plane(),
                affineContentSpace(new Matrix4d(), affine.sample(), scene.environment()));
        }

        private void updateAffine() {
            ViewStreamMessage.PortalAffine sample = scene.affine();
            if (sample == null && affine == null || affine != null && sample == affine.sample()) {
                return;
            }
            parentClip = null;
            AffinePortalSurface previous = affine;
            affine = sample == null ? null : new AffinePortalSurface(sample, previous);
            if (affine == null || !affine.sameShape(previous)) {
                closeAperture(this);
            }
            if (affine == null) {
                updateGeometry();
            } else {
                bounds = affine.bounds();
            }
        }

        private void updateGeometry() {
            geometry = scene.geometry();
            aperture = AperturePolygon.from(geometry);
            Vec3d min = aperture.point(0, 0);
            Vec3d max = aperture.point(scene.geometry().apertureWidth(), scene.geometry().apertureHeight());
            bounds = new AABB(min.x(), min.y(), min.z(), max.x(), max.y(), max.z()).inflate(0.01);
        }
    }

    private static final class Section {
        private final long key;
        private long revision;
        private PortalScene.MeshIdentity identity;
        private long retainedSequence;
        private double sortDistance;
        private final AABB bounds;
        private final Map<RenderType, PortalGpuMesh> layers = new IdentityHashMap<>();

        private Section(long key, long revision) {
            this.key = key;
            this.revision = revision;
            int x = SectionPos.x(key) << 4;
            int y = SectionPos.y(key) << 4;
            int z = SectionPos.z(key) << 4;
            bounds = new AABB(x - 1, y - 1, z - 1, x + 17, y + 17, z + 17);
        }
    }
}
