package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.client.ClientMeshWorld;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesClientConfig;

import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeMesh;
import art.arcane.wormholes.portal.ApertureKind;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.PipelineCache;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.util.Util;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4d;
import org.joml.Quaternionf;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.TreeMap;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientPortalRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_GPU_BYTES = 128L * 1024L * 1024L;
    static final long RETRY_NANOS = 5_000_000_000L;
    private static final long BUILD_DISPATCH_NANOS = 2_000_000L;
    private static final Vector4fc WHITE = new Vector4f(1.0f);
    private static final Matrix4fc IDENTITY_TEXTURE = new Matrix4f();
    private static final Comparator<Section> FARTHER_SECTION_FIRST = Comparator.comparingDouble((Section section) -> section.sortDistance).reversed();
    private static final ClientPortalRenderer INSTANCE = new ClientPortalRenderer();
    private final Int2ObjectOpenHashMap<Portal> portals = new Int2ObjectOpenHashMap<>();
    private final ExecutorService compiler = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Wormholes portal mesher").factory());
    private final ConcurrentLinkedQueue<MeshCompletion> meshCompletions = new ConcurrentLinkedQueue<>();
    private final List<Portal> visible = new ArrayList<>();
    private final List<PortalShaderRenderer.DemandView> shaderDemand = new ArrayList<>();
    private final List<Portal> shaderRoots = new ArrayList<>();
    private final List<Portal> buildDemand = new ArrayList<>();
    private BlockStateModelSet models;
    private PortalPipelines pipelines;
    private PortalShaderRenderer shaderRenderer;
    private List<PortalShaderRenderer.Resolution> shaderSizes;
    private int frameWidth;
    private int frameHeight;
    private CameraRenderState camera;
    private CameraRenderState rootCamera;
    private final PortalRenderTargets targets = new PortalRenderTargets();
    private TextureTarget portalLayer;
    private PortalGpuMesh layerMesh;
    private final Matrix4f frameProjection = new Matrix4f();
    private Portal travel;
    private Portal arrival;
    private Portal travelSource;
    private EnvironmentState travelSourceEnvironment;
    private PortalShaderRenderer.Session travelSourceShaders;
    private CameraRenderState travelCamera;
    private final CameraRenderState travelDisplayCamera = new CameraRenderState();
    private final LongOpenHashSet travelDrawSections = new LongOpenHashSet();
    private final LongOpenHashSet mainDrawn = new LongOpenHashSet();
    private boolean travelTransition;
    private boolean travelDrawn;
    private long travelMeshEpoch;
    private long travelDrawEpoch;
    private boolean travelMainReady;
    private int pendingBuilds;
    private int residentBuilds;
    private int lastBuildPortal;
    private long buildBudgetNanos = BUILD_DISPATCH_NANOS;
    private long retainedSequence;
    private long gpuBytes;
    private final LinkedHashMap<RetainedMeshKey, Section> retainedMeshes = new LinkedHashMap<>();
    private final NavigableMap<Long, RetainedMeshKey> retainedMeshOrder = new TreeMap<>();
    private final IdentityHashMap<Object, Integer> retainedProofReferences = new IdentityHashMap<>();
    private long retainedProofBytes;
    private GpuSampler terrainSampler;
    private ByteBufferBuilder translucentSorting;
    private boolean rgss;
    private int anisotropy;
    private PortalEnvironmentRenderer nativeEnvironment;
    private boolean ambientOcclusion;

    private ClientPortalRenderer() {
    }

    public static ClientPortalRenderer instance() {
        return INSTANCE;
    }

    public String debugLine() {
        int sections = 0;
        int failures = 0;
        for (Portal portal : portals.values()) {
            sections += portal.sections.size();
            failures += portal.failure == null ? 0 : 1;
        }
        return "gpu=" + portals.size() + "/" + visible.size() + " mesh=" + sections + " pending=" + pendingBuilds
            + " shaderKiB=" + (shaderRenderer == null ? 0 : shaderRenderer.bytes() >> 10) + " gpuKiB=" + (gpuBytes >> 10) + " targetKiB=" + (targets.bytes() >> 10) + " unavailable=" + failures;
    }

    public ShapeMesh apertureShape(int portalKey) {
        Portal portal = portals.get(portalKey);
        return portal == null || portal.apertureMesh == null ? null : portal.apertureShape;
    }

    public boolean available(int portalKey) {
        Portal portal = portals.get(portalKey);
        if (portal == null) {
            return false;
        }
        retry(portal, System.nanoTime());
        return portal.active;
    }

    public void prepareTravel(PortalScene scene, CameraRenderState arrivalCamera) {
        cancelTravel();
        travel = new Portal(arrival != null && arrival.key == -1 ? -2 : -1, scene);
        travelCamera = arrivalCamera;
        portals.put(travel.key, travel);
    }

    public void prepareTravelSource(ClientTravelScene scene) {
        EnvironmentState environment = scene.environment();
        if (environment != null && environment.equals(travelSourceEnvironment)) {
            Portal previous = portals.remove(-3);
            if (previous != null) {
                previous.active = false;
                release(previous);
            }
        } else {
            retireTravelSource();
            prepareTravelSourceEnvironment(environment);
        }
        travelSource = new Portal(-3, scene);
        portals.put(travelSource.key, travelSource);
    }

    public void retireTravelSource() {
        remove(-3);
        travelSource = null;
        travelSourceEnvironment = null;
        travelSourceShaders = null;
    }

    public void prepareTravelSourceEnvironment(EnvironmentState environment) {
        if (!Objects.equals(travelSourceEnvironment, environment)) {
            if (shaderRenderer != null) {
                shaderRenderer.remove(-3);
            }
            travelSourceShaders = null;
        }
        travelSourceEnvironment = environment;
    }

    public boolean travelSourceShaderReady() {
        return !PortalShaderScope.shaders() || travelSourceShaders != null && travelSourceShaders.ready();
    }

    public void updateTravelCamera(Camera source, Similarity sourceToDestination) {
        if (travel == null || travelTransition || !source.isInitialized()) {
            return;
        }
        travelDisplayCamera.pos = source.position();
        travelDisplayCamera.blockPos = source.blockPosition();
        source.getViewRotationMatrix(travelDisplayCamera.viewRotationMatrix);
        source.getViewRotationProjectionMatrix(travelDisplayCamera.projectionMatrix);
        travelDisplayCamera.projectionMatrix.mul(new Matrix4f(travelDisplayCamera.viewRotationMatrix).invert());
        travelDisplayCamera.orientation.set(source.rotation());
        travelDisplayCamera.xRot = source.xRot();
        travelDisplayCamera.yRot = source.yRot();
        travelDisplayCamera.hudFov = source.getFov();
        travelDisplayCamera.isFirstPerson = !source.isDetached();
        travelDisplayCamera.initialized = true;
        travelCamera = transformedCamera(travelDisplayCamera, PortalProjection.matrix(sourceToDestination.inverse()), travelDisplayCamera.projectionMatrix);
        travel.cullFrustum = new Frustum(travelCamera.viewRotationMatrix, travelCamera.projectionMatrix);
        travel.cullFrustum.prepare(travelCamera.pos.x, travelCamera.pos.y, travelCamera.pos.z);
    }

    public boolean travelReady() {
        if (travel == null || !travel.active || !travelDrawn || travelDrawEpoch != travelMeshEpoch
            || travel.shader == null && shaderRenderer != null) {
            return false;
        }
        for (LongIterator iterator = travel.scene.sectionKeys().iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            if (!inTravelFrustum(travel, key)) {
                continue;
            }
            long revision = travel.scene.revision(key);
            if (revision < 0) {
                return false;
            }
            if (travel.scene.empty(key)) {
                continue;
            }
            Section section = travel.sections.get(key);
            if (section == null || section.revision != revision || travel.dirty.contains(key) || travel.building.contains(key)
                || !section.layers.isEmpty() && !travelDrawSections.contains(key)) {
                return false;
            }
        }
        return true;
    }

    public boolean travelCovered() {
        if (!travelDrawable()) {
            return false;
        }
        for (LongIterator iterator = travel.scene.sectionKeys().iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            if (!travel.sections.containsKey(key) && inTravelFrustum(travel, key)
                && (travel.scene.revision(key) < 0 || !travel.scene.empty(key))) {
                return false;
            }
        }
        return true;
    }

    public boolean travelMainReady() {
        return travelTransition && travelMainReady;
    }

    public boolean travelDrawable() {
        if (travel == null || !travel.active || !travelDrawn) {
            return false;
        }
        if (shaderRenderer == null) {
            TextureTarget target = targets.travel(travel.key);
            return target != null && target.getColorTexture() != null && target.getDepthTexture() != null;
        }
        if (travel.shader == null || !travel.shader.ready()) {
            return false;
        }
        RenderTarget target = travel.shader.target();
        return target != null && target.getColorTexture() != null && target.getDepthTexture() != null;
    }

    public void invalidateTravel(long sectionKey) {
        if (travel != null) {
            invalidate(travel.key, sectionKey, true);
        }
    }

    public void transitionTravel(boolean value) {
        if (value) {
            retireArrival();
        }
        travelTransition = value;
        travelMainReady = false;
    }

    public void cancelTravel() {
        if (travel != null) {
            targets.releaseTravel(travel.key);
            remove(travel.key);
        }
        travel = null;
        travelCamera = null;
        travelTransition = false;
        travelDrawn = false;
        travelDrawSections.clear();
        travelMeshEpoch = 0;
        travelDrawEpoch = 0;
        travelMainReady = false;
    }

    public void retainArrival() {
        retireArrival();
        arrival = travel;
        travel = null;
        travelCamera = null;
        travelTransition = false;
        travelDrawn = false;
        travelDrawSections.clear();
        travelMeshEpoch = 0;
        travelDrawEpoch = 0;
        travelMainReady = false;
    }

    public void retireArrival() {
        if (arrival != null) {
            targets.releaseTravel(arrival.key);
            remove(arrival.key);
            arrival = null;
        }
        travelMainReady = false;
    }

    public boolean arrivalMainReady() {
        return arrival != null && travelMainReady;
    }

    public boolean arrivalDrawable() {
        if (arrival == null || !arrival.active) {
            return false;
        }
        RenderTarget target = arrival.shader == null ? targets.travel(arrival.key) : arrival.shader.target();
        return target != null && target.getColorTexture() != null && target.getDepthTexture() != null;
    }

    public void invalidateArrival(long key) {
        if (arrival != null) {
            invalidate(arrival.key, key, true);
        }
    }

    public boolean coversLocalPlayer(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        Portal cover = arrival == null ? travelTransition ? travel : null : arrival;
        if (entity != minecraft.player || minecraft.level == null || entity.level() != minecraft.level
            || cover == null || !cover.active || !cover.rendered || cover.target == null
            || cover.target.getColorTexture() == null || cover.target.getDepthTexture() == null
            || !(cover.scene instanceof ClientTravelScene scene) || !scene.inLevel(minecraft.level)) {
            return false;
        }
        BlockPos position = entity.blockPosition();
        if (minecraft.level.getChunkSource().getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false) == null) {
            return false;
        }
        long key = SectionPos.asLong(position);
        long revision = scene.revision(key);
        if (revision < 0 || cover.dirty.contains(key) || cover.building.contains(key)) {
            return false;
        }
        Section section = cover.sections.get(key);
        return scene.empty(key) || section != null && section.revision == revision;
    }

    public boolean coversMainSection(long key) {
        Minecraft minecraft = Minecraft.getInstance();
        Portal cover = arrival == null ? travelTransition ? travel : null : arrival;
        if (minecraft.level == null || cover == null || !cover.active || cover.target == null
            || cover.target.getColorTexture() == null || cover.target.getDepthTexture() == null
            || !(cover.scene instanceof ClientTravelScene scene) || !scene.inLevel(minecraft.level)) {
            return false;
        }
        if (minecraft.level.getChunkSource().getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false) == null) {
            return false;
        }
        long revision = scene.revision(key);
        Section section = cover.sections.get(key);
        return revision >= 0 && section != null && section.revision == revision
            && !cover.dirty.contains(key) && !cover.building.contains(key);
    }

    private boolean mainOwnsTravelSection(Portal portal, long key) {
        Portal cover = arrival == null ? travelTransition ? travel : null : arrival;
        return portal == cover && camera == portal.camera && coversMainSection(key)
            && Minecraft.getInstance().levelRenderer.isSectionCompiledAndVisible(new BlockPos(
                (SectionPos.x(key) << 4) + 8, (SectionPos.y(key) << 4) + 8, (SectionPos.z(key) << 4) + 8), 0);
    }

    public boolean coversEndPortalSurface(BlockPos position) {
        for (Portal portal : portals.values()) {
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

    public void captureProjection(Matrix4f projection) {
        frameProjection.set(projection);
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
        if (shaderRenderer != null && (changed || resetHistory)) {
            shaderRenderer.resetHistory(portalKey);
        }
        if (!changed) {
            if (!apertureSettingsCurrent(portal)) {
                closeAperture(portal);
            }
            return;
        }
        portal.updateGeometry();
        closeAperture(portal);
        if (portal.parentClip != null) {
            portal.parentClip.close();
            portal.parentClip = null;
        }
        for (Section section : portal.sections.values()) {
            if (section.clip != null) {
                section.clip.close();
                section.clip = null;
            }
        }
    }

    public void remove(int portalKey) {
        Portal removed = portals.remove(portalKey);
        if (shaderRenderer != null) {
            shaderRenderer.remove(portalKey);
        }
        if (removed != null) {
            removed.active = false;
            release(removed);
        }
    }

    public void sourcePipelineDestroying(Object shaderPack) {
        if (shaderRenderer != null && !shaderRenderer.usesPack(shaderPack)) {
            resourceReload();
        }
    }

    public void clear() {
        ClientSodiumTerrain.clear();
        clearRetainedMeshes();
        arrival = null;
        travelSource = null;
        travelSourceEnvironment = null;
        travelSourceShaders = null;
        travel = null;
        travelCamera = null;
        travelTransition = false;
        travelDrawn = false;
        releaseFrameTargets();
        if (shaderRenderer != null) {
            shaderRenderer.disconnect();
            shaderRenderer = null;
        }
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
        if (pipelines != null) {
            pipelines.close();
            pipelines = null;
        }
        if (terrainSampler != null) {
            terrainSampler.close();
            terrainSampler = null;
        }
    }

    public void invalidate(int portalKey, long sectionKey, boolean changed) {
        Portal portal = portals.get(portalKey);
        if (portal != null) {
            retainedMeshesChanged(portal);
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

    public void resourceReload() {
        ClientSodiumTerrain.clear();
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.preparedTravel().discardSourcePreparation();
        }
        clearRetainedMeshes();
        buildDemand.clear();
        releaseFrameTargets();
        if (shaderRenderer != null) {
            shaderRenderer.close();
            shaderRenderer = null;
        }
        for (Portal portal : portals.values()) {
            portal.generation++;
            portal.materials = PortalTerrainMaterials.VANILLA;
            portal.evicted.clear();
            release(portal);
            for (LongIterator iterator = portal.scene.sectionKeys().iterator(); iterator.hasNext();) {
                portal.dirty.add(iterator.nextLong());
            }
        }
        clearRetainedMeshes();
        if (pipelines != null) {
            pipelines.close();
            pipelines = null;
        }
        models = null;
    }

    public void finishBuilds() {
        MeshCompletion completion;
        while ((completion = meshCompletions.poll()) != null) {
            finish(completion.portal(), completion.key(), completion.revision(), completion.generation(), completion.identity(), completion.mesh(), completion.failure());
        }
    }

    public void prepare(CameraRenderState camera, GpuBufferSlice fog) {
        if (PortalShaderScope.shadowPass()) {
            return;
        }
        finishBuilds();
        this.camera = camera;
        this.rootCamera = camera;
        visible.clear();
        buildDemand.clear();
        try {
            retryUnavailable(System.nanoTime());
            if (!hasActivePortals()) {
                releaseFrameTargets();
                return;
            }
            try (PortalShaderScope scope = PortalShaderScope.rendering();
                 PortalTextureScope.Batch textures = PortalTextureScope.batch();
                 PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
                prepareFrame(fog);
            }
        } catch (RuntimeException failure) {
            failFrame(failure);
        } finally {
            buildDemand.clear();
            RenderSystem.setShaderFog(fog);
            this.camera = rootCamera;
        }
    }

    private boolean hasActivePortals() {
        for (Portal portal : portals.values()) {
            if (portal.active) {
                return true;
            }
        }
        return false;
    }

    private void prepareFrame(GpuBufferSlice fog) {
        buildBudgetNanos = BUILD_DISPATCH_NANOS;
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSet currentModels = minecraft.getModelManager().getBlockStateModelSet();
        boolean shaders = PortalShaderScope.shaders();
        boolean currentAmbientOcclusion = minecraft.options.ambientOcclusion().get();
        if (models != currentModels || currentAmbientOcclusion != ambientOcclusion || shaders != (shaderRenderer != null)) {
            clearRetainedMeshes();
            resourceReload();
            models = currentModels;
            ambientOcclusion = currentAmbientOcclusion;
            if (shaders) {
                shaderRenderer = PortalShaderRenderer.create();
            }
        }
        boolean requestedRgss = minecraft.options.textureFiltering().get() == TextureFilteringMethod.RGSS;
        if (pipelines == null || requestedRgss != rgss) {
            if (pipelines != null) {
                pipelines.close();
            }
            rgss = requestedRgss;
            pipelines = new PortalPipelines(rgss);
        }
        int requestedAnisotropy = minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
            ? minecraft.options.maxAnisotropyValue() : 1;
        if (terrainSampler == null || requestedAnisotropy != anisotropy) {
            if (terrainSampler != null) {
                terrainSampler.close();
            }
            anisotropy = requestedAnisotropy;
            terrainSampler = RenderSystem.getDevice().createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR, FilterMode.LINEAR, anisotropy, OptionalDouble.empty());
        }
        RenderSystem.setShaderFog(fog);
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        frameWidth = main.width;
        frameHeight = main.height;
        RenderDimensions dimensions = new RenderDimensions(main.width, main.height, 0);
        if (shaderRenderer != null) {
            collectShaderDemand();
            shaderSizes = shaderRenderer.resolution(new PortalShaderRenderer.Sizing(main.width, main.height, shaderDemand));
            PortalShaderRenderer.Resolution resolution = shaderSizes.getFirst();
            dimensions = new RenderDimensions(resolution.width(), resolution.height(), 0);
            prewarmShaders(dimensions);
        }
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
            if (!portal.scene.fullWorld() && portal.scene.geometry().parentPortalKey() == 0 && renderTree(portal, new Matrix4d(), null, dimensions)) {
                if (portalLayer == null) {
                    portalLayer = targets.layer(main.width, main.height);
                    try (RenderPass clear = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes portal layer clear",
                        portalLayer.getColorTextureView(), Optional.of(new Vector4f(0.0f)), portalLayer.getDepthTextureView(), OptionalDouble.of(0.0))) {
                    }
                }
                visible.add(portal);
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes aperture layer",
                    portalLayer.getColorTextureView(), Optional.empty(), portalLayer.getDepthTextureView(), OptionalDouble.empty())) {
                    composite(portal, null, pass);
                }
            }
        }
        renderTravel(dimensions);
        if (arrival != null && arrival.active) {
            renderTravelView(arrival, rootCamera, true, dimensions);
        }
        warmTravelSource(dimensions);
        ClientSodiumTerrain.warmPrepared();
        dispatchBuilds();
        for (Portal portal : portals.values()) {
            if (!portal.rendered) {
                releaseTarget(portal);
            }
        }
        targets.endFrame();
        this.camera = rootCamera;
    }

    TextureTarget nativeTravelTarget() {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        return targets.travel(-4, main.width, main.height);
    }

    void prepareNativeSky(EnvironmentState environment, CameraRenderState camera) {
        if (nativeEnvironment == null) {
            nativeEnvironment = new PortalEnvironmentRenderer();
        }
        nativeEnvironment.prepare(environment, camera);
    }

    PortalEnvironmentRenderer nativeEnvironment() {
        return nativeEnvironment;
    }

    void renderNativeSky() {
        nativeEnvironment.renderSky(targets.travelSky(-4));
    }

    GpuBufferSlice nativeProjection(Matrix4fc projection) {
        return targets.projection(PortalRenderTargets.DEPTHS - 1).getBuffer(new Matrix4f(projection));
    }

    private void warmTravelSource(RenderDimensions dimensions) {
        Portal source = travelSource;
        if (travelSourceEnvironment == null || source != null && !source.active) {
            return;
        }
        boolean nativeTerrain = source == null || nativeTravelTerrain(source);
        CameraRenderState previous = camera;
        camera = rootCamera;
        if (source != null) {
            source.camera = rootCamera;
            source.cullFrustum = rootCamera.cullFrustum;
        }
        try {
            if (shaderRenderer == null) {
                if (!nativeTerrain) {
                    materialContext(source, PortalTerrainMaterials.VANILLA);
                }
            } else {
                PortalShaderRenderer.Session session = shaderRenderer.acquire(-3, travelSourceEnvironment, dimensions.width(), dimensions.height());
                travelSourceShaders = session;
                if (source != null) {
                    source.destination = session;
                    source.shader = session.ready() ? session : null;
                }
                if (!session.ready()) {
                    PortalShaderCamera shaderCamera = new PortalShaderCamera(travelSourceEnvironment, camera);
                    session.warm(new PortalShaderContext.View(travelSourceEnvironment, shaderCamera, session.target(),
                        shaderCamera.getViewRotationMatrix(new Matrix4f()), frameProjection));
                }
                if (!nativeTerrain) {
                    materialContext(source, session.materials());
                }
            }
            if (!nativeTerrain) {
                maintain(source);
            }
        } finally {
            camera = previous;
        }
    }

    private void renderTravel(RenderDimensions dimensions) {
        if (travel == null || !travel.active || nativeTravelTerrain(travel)) {
            return;
        }
        if (!travelTransition && travelReady()) {
            travel.target = travel.shader == null ? targets.travel(travel.key) : travel.shader.target();
            travel.rendered = true;
            return;
        }
        renderTravelView(travel, travelTransition ? rootCamera : travelCamera, travelTransition, dimensions);
    }

    private void renderTravelView(Portal portal, CameraRenderState arrival, boolean transition, RenderDimensions dimensions) {
        if (nativeTravelTerrain(portal)) {
            return;
        }
        CameraRenderState previous = camera;
        if (!transition) {
            arrival.projectionMatrix.set(frameProjection);
            arrival.cameraEntityPartialTicks = rootCamera.cameraEntityPartialTicks;
            arrival.depthFar = rootCamera.depthFar;
        }
        portal.camera = transformedCamera(arrival, new Matrix4d(), frameProjection);
        portal.contentCamera = portal.camera;
        camera = portal.camera;
        portal.cullFrustum = new Frustum(camera.viewRotationMatrix, frameProjection);
        portal.cullFrustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
        if (!transition) {
            arrival.cullFrustum = portal.cullFrustum;
        }
        portal.viewport = new PortalViewport(0, 0, frameWidth, frameHeight);
        portal.toRoot.identity();
        try {
            if (shaderRenderer != null) {
                PortalShaderRenderer.Session session = shaderRenderer.acquire(portal.key, portal.scene.environment(), frameWidth, frameHeight);
                if (!session.ready()) {
                    PortalShaderCamera shaderCamera = new PortalShaderCamera(portal.scene.environment(), camera);
                    session.warm(new PortalShaderContext.View(portal.scene.environment(), shaderCamera, session.target(),
                        shaderCamera.getViewRotationMatrix(new Matrix4f()), frameProjection));
                }
            }
            if (!renderPortal(portal, dimensions)) {
                return;
            }
            portal.rendered = true;
            if (portal == travel) {
                travelDrawn = true;
                travelDrawEpoch = travelMeshEpoch;
                travelDrawSections.clear();
                for (Section section : portal.drawSections) {
                    travelDrawSections.add(section.key);
                }
            }
        } finally {
            camera = previous;
        }
    }

    private void collectShaderDemand() {
        shaderDemand.clear();
        shaderRoots.clear();
        if (travelSourceEnvironment != null) {
            shaderDemand.add(new PortalShaderRenderer.DemandView(-3, travelSourceEnvironment, 0));
        }
        Vec3d eye = new Vec3d(rootCamera.pos.x, rootCamera.pos.y, rootCamera.pos.z);
        Matrix4d viewProjection = new Matrix4d(frameProjection).mul(new Matrix4d(rootCamera.viewRotationMatrix))
            .translate(-rootCamera.pos.x, -rootCamera.pos.y, -rootCamera.pos.z);
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        for (Portal portal : portals.values()) {
            if (!portal.scene.fullWorld() && portal.scene.geometry().parentPortalKey() == 0 && portal.active && portal.scene.environment() != null
                && portal.aperture.servesEye(eye) && visiblePortal(portal, eye)
                && PortalViewport.coverage(portal.aperture, viewProjection, frameWidth, frameHeight, zeroToOne) != null) {
                shaderRoots.add(portal);
                collectShaderTree(portal, 0);
            }
        }
        if (!shaderRoots.isEmpty()) {
            return;
        }
        Portal closest = null;
        double distance = 64.0 * 64.0;
        for (Portal portal : portals.values()) {
            if (portal.scene.fullWorld() || portal.scene.geometry().parentPortalKey() != 0 || !portal.active || portal.scene.environment() == null
                || !portal.aperture.servesEye(eye)) {
                continue;
            }
            double candidate = portal.bounds.getCenter().distanceToSqr(rootCamera.pos);
            if (candidate < distance) {
                distance = candidate;
                closest = portal;
            }
        }
        if (closest != null) {
            shaderRoots.add(closest);
            collectShaderTree(closest, 0);
        }
    }

    private void prewarmShaders(RenderDimensions dimensions) {
        try {
            for (Portal portal : shaderRoots) {
                if (prewarmTree(portal, new Matrix4d(), dimensions)) {
                    return;
                }
            }
        } finally {
            camera = rootCamera;
        }
    }

    private boolean prewarmTree(Portal portal, Matrix4d toRoot, RenderDimensions dimensions) {
        if (!portal.active || portal.scene.environment() == null || dimensions.depth() >= PortalRenderTargets.DEPTHS) {
            return false;
        }
        try {
            PortalShaderRenderer.Session session = shaderRenderer.acquire(portal.key, portal.scene.environment(), dimensions.width(), dimensions.height());
            Matrix4d content = contentSpace(portal, toRoot);
            CameraRenderState display = transformedCamera(rootCamera, content, frameProjection);
            camera = display;
            portal.camera = display;
            portal.contentCamera = display;
            portal.cullFrustum = display.cullFrustum;
            if (portal.cullFrustum == null) {
                portal.cullFrustum = new Frustum(display.viewRotationMatrix, frameProjection);
                portal.cullFrustum.prepare(display.pos.x, display.pos.y, display.pos.z);
            }
            if (!session.ready()) {
                PortalShaderCamera shaderCamera = new PortalShaderCamera(portal.scene.environment(), display);
                PortalShaderContext.View view = new PortalShaderContext.View(portal.scene.environment(), shaderCamera, session.target(),
                    shaderCamera.getViewRotationMatrix(new Matrix4f()), frameProjection);
                session.warm(view);
                return true;
            }
            Matrix4d childSpace = new Matrix4d(content).mul(PortalProjection.destinationToSource(portal.scene.environment().transform()));
            for (Portal child : portals.values()) {
                if (child.scene.geometry().parentPortalKey() == portal.key && prewarmTree(child, childSpace,
                    childDimensions(dimensions))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException failure) {
            fail(portal, Failure.FRAME, failure);
            return true;
        }
    }

    private void collectShaderTree(Portal portal, int depth) {
        if (!portal.active || portal.scene.environment() == null || depth >= PortalRenderTargets.DEPTHS) {
            return;
        }
        shaderDemand.add(new PortalShaderRenderer.DemandView(portal.key, portal.scene.environment(), depth));
        for (Portal child : portals.values()) {
            if (child.scene.geometry().parentPortalKey() == portal.key) {
                collectShaderTree(child, depth + 1);
            }
        }
    }

    public void composite(RenderPass pass) {
        if (PortalShaderScope.shadowPass() || PortalShaderScope.shaders()) {
            return;
        }
        compositeLayer(pass);
    }

    public void compositeAfterShaders() {
        if (PortalShaderContext.current() != null || !PortalShaderScope.shaders()) {
            return;
        }
        Portal cover = arrival == null ? travelTransition ? travel : null : arrival;
        if (layerMesh == null || (visible.isEmpty() || portalLayer == null)
            && (cover == null || !cover.rendered || cover.target == null)) {
            travelMainReady = false;
            return;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try (PortalFramebufferScope framebuffer = PortalFramebufferScope.capture();
             RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes shader apertures",
                 main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
            compositeLayer(pass);
        }
    }

    private void compositeLayer(RenderPass pass) {
        try {
            if (layerMesh == null) {
                return;
            }
            try (PortalShaderScope scope = PortalShaderScope.rendering()) {
                Portal cover = arrival == null ? travelTransition ? travel : null : arrival;
                if (cover != null && cover.rendered && cover.target != null) {
                    drawLayer(pass, cover.target);
                }
                if (!visible.isEmpty() && portalLayer != null) {
                    drawLayer(pass, portalLayer);
                }
                travelMainReady = mainTravelCoverageReady();
            }
        } catch (RuntimeException failure) {
            failFrame(failure);
        } finally {
            camera = rootCamera;
        }
    }

    private boolean mainTravelCoverageReady() {
        Portal cover = arrival == null ? travelTransition ? travel : null : arrival;
        if (cover == null || !cover.rendered || cover.cullFrustum == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (cover.scene instanceof ClientTravelScene scene && !scene.inLevel(minecraft.level)) {
            return false;
        }
        LevelRenderer renderer = minecraft.levelRenderer;
        long fade = Util.toMillis(minecraft.options.chunkSectionFadeInTime().get());
        boolean indexed = false;
        for (LongIterator iterator = cover.scene.sectionKeys().iterator(); iterator.hasNext();) {
            long key = iterator.nextLong();
            if (cover.scene.empty(key) || !visibleSection(cover, key)) {
                continue;
            }
            if (!indexed && !indexMainDrawn(renderer)) {
                return false;
            }
            indexed = true;
            if (mainDrawn.contains(key) && !renderer.isSectionCompiledAndVisible(new BlockPos((SectionPos.x(key) << 4) + 8,
                (SectionPos.y(key) << 4) + 8, (SectionPos.z(key) << 4) + 8), fade)) {
                return false;
            }
        }
        return true;
    }

    private boolean indexMainDrawn(LevelRenderer renderer) {
        ObjectArrayList<SectionRenderDispatcher.RenderSection> drawn = renderer.visibleSections();
        mainDrawn.clear();
        for (int index = 0; index < drawn.size(); index++) {
            mainDrawn.add(drawn.get(index).getSectionNode());
        }
        return !drawn.isEmpty();
    }

    private void drawLayer(RenderPass pass, RenderTarget layer) {
        pass.setPipeline(pipelines.layer());
        pass.setUniform("Sampler0", layer.getColorTextureView(), sampler());
        pass.setUniform("Sampler1", layer.getDepthTextureView(), sampler());
        layerMesh.draw(pass);
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
        if (!portal.active || portal.scene.environment() == null || portal.rendering || portal.rendered || dimensions.depth() >= PortalRenderTargets.DEPTHS) {
            return false;
        }
        if (!portal.toRoot.equals(toRoot) && portal.parentClip != null) {
            portal.parentClip.close();
            portal.parentClip = null;
        }
        portal.toRoot.set(toRoot);
        portal.contentToRoot.set(contentSpace(portal, toRoot));
        portal.camera = transformedCamera(rootCamera, toRoot, frameProjection);
        portal.contentCamera = portal.contentToRoot.equals(toRoot) ? portal.camera : transformedCamera(rootCamera, portal.contentToRoot, frameProjection);
        camera = portal.camera;
        Vec3d eye = new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z);
        if (!portal.aperture.servesEye(eye)) {
            return false;
        }
        if (!visiblePortal(portal, eye)) {
            return false;
        }
        Matrix4d viewProjection = new Matrix4d(frameProjection).mul(new Matrix4d(camera.viewRotationMatrix))
            .translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        PortalViewport viewport = PortalViewport.coverage(portal.aperture, viewProjection, dimensions.width(), dimensions.height(),
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        if (viewport != null && parentViewport != null) {
            viewport = viewport.intersect(parentViewport);
        }
        if (viewport == null) {
            return false;
        }
        portal.viewport = viewport;
        camera = portal.contentCamera;
        portal.cullFrustum = viewport.frustum(camera, frameProjection, dimensions.width(), dimensions.height());
        portal.rendering = true;
        if (!renderPortal(portal, dimensions)) {
            return false;
        }
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
        return contentSpace(toRoot, portal.scene.geometry(), portal.scene.environment().scale());
    }

    static CameraRenderState transformedCamera(CameraRenderState rootCamera, Matrix4d toRoot, Matrix4f projection) {
        if (toRoot.equals(new Matrix4d())) {
            return rootCamera;
        }
        Vector3d position = new Matrix4d(toRoot).invert().transformPosition(new Vector3d(rootCamera.pos.x, rootCamera.pos.y, rootCamera.pos.z));
        CameraRenderState result = new CameraRenderState();
        result.pos = new Vec3(position.x, position.y, position.z);
        result.blockPos = BlockPos.containing(result.pos);
        result.viewRotationMatrix = new Matrix4f(rootCamera.viewRotationMatrix).mul(new Matrix4f(toRoot).m30(0).m31(0).m32(0));
        Matrix4f billboardView = new Matrix4f(result.viewRotationMatrix);
        if (billboardView.determinant3x3() < 0) {
            billboardView.m00(-billboardView.m00()).m10(-billboardView.m10()).m20(-billboardView.m20());
        }
        result.orientation = new Quaternionf().setFromUnnormalized(billboardView.invert());
        result.projectionMatrix = projection;
        result.cullFrustum = new Frustum(result.viewRotationMatrix, projection);
        result.cullFrustum.prepare(position.x, position.y, position.z);
        result.cameraEntityPartialTicks = rootCamera.cameraEntityPartialTicks;
        result.fogData = rootCamera.fogData;
        result.fogType = rootCamera.fogType;
        result.entityRenderState = rootCamera.entityRenderState;
        result.depthFar = rootCamera.depthFar;
        result.initialized = true;
        result.isFirstPerson = rootCamera.isFirstPerson;
        result.hudFov = rootCamera.hudFov;
        return result;
    }

    private void composite(Portal portal, Portal parent, RenderPass pass) {
        if (portal.target == null || portal.apertureMesh == null) {
            return;
        }
        camera = portal.camera;
        boolean shaped = portal.aperture.hasShape();
        pass.setPipeline(shaped ? pipelines.compositeShape() : pipelines.composite());
        RenderSystem.bindDefaultUniforms(pass);
        GpuBufferSlice transform = transform(portal.scene.geometry().originX(), portal.scene.geometry().originY(), portal.scene.geometry().originZ());
        pass.setUniform("DynamicTransforms", transform);
        GpuBuffer clip = portal.compositeUniform;
        if (parent != null) {
            if (portal.parentClip != null && (portal.parentClipGeometry != parent.scene.geometry()
                || !portal.parentClipToRoot.equals(parent.toRoot)
                || portal.parentClipWidth != parent.target.width || portal.parentClipHeight != parent.target.height)) {
                portal.parentClip.close();
                portal.parentClip = null;
            }
            if (portal.parentClip == null) {
                portal.parentClipGeometry = parent.scene.geometry();
                portal.parentClipToRoot.set(parent.toRoot);
                portal.parentClipWidth = parent.target.width;
                portal.parentClipHeight = parent.target.height;
                AperturePolygon.Plane plane = parent.aperture.plane();
                float side = parent.scene.geometry().frontSide() ? 1 : -1;
                Matrix4d childToParent = new Matrix4d(parent.toRoot).invert().mul(portal.toRoot);
                Vector4d transformed = childToParent.transpose().transform(new Vector4d(plane.x(), plane.y(), plane.z(), plane.offset()));
                double offset = transformed.w + transformed.x * portal.scene.geometry().originX()
                    + transformed.y * portal.scene.geometry().originY() + transformed.z * portal.scene.geometry().originZ();
                portal.parentClip = uniform(new Vector4f(side * (float) transformed.x, side * (float) transformed.y,
                    side * (float) transformed.z, side * (float) offset), new PortalViewport(0, 0, parent.target.width, parent.target.height));
            }
            clip = portal.parentClip;
        }
        pass.setUniform("Portal", clip);
        pass.setUniform("Sampler0", portal.target.getColorTextureView(), sampler());
        portal.apertureMesh.draw(pass);
        if (shaped && portal.apertureFeather > 0.0F) {
            pass.setPipeline(pipelines.feather());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.setUniform("Portal", clip);
            pass.setUniform("Feather", featherUniform(portal));
            portal.apertureMesh.draw(pass);
        }
    }

    private static GpuBuffer featherUniform(Portal portal) {
        EnvironmentState.Color color = portal.scene.environment().fog().color();
        if (portal.featherUniform != null && color.equals(portal.featherColor) && portal.featherWidth == portal.apertureFeather) {
            return portal.featherUniform;
        }
        if (portal.featherUniform != null) {
            portal.featherUniform.close();
        }
        ByteBuffer data = MemoryUtil.memAlloc(32);
        try {
            data.putFloat(color.red()).putFloat(color.green()).putFloat(color.blue()).putFloat(1.0f);
            data.putFloat(portal.apertureFeather).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f).flip();
            portal.featherUniform = RenderSystem.getDevice().createBuffer(() -> "Portal edge feather", GpuBuffer.USAGE_UNIFORM, data);
        } finally {
            MemoryUtil.memFree(data);
        }
        portal.featherColor = color;
        portal.featherWidth = portal.apertureFeather;
        return portal.featherUniform;
    }

    private void maintain(Portal portal) {
        if (nativeTravelTerrain(portal)) {
            return;
        }
        int cameraSectionX = camera.blockPos.getX() >> 4;
        int cameraSectionY = camera.blockPos.getY() >> 4;
        int cameraSectionZ = camera.blockPos.getZ() >> 4;
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
                retainedMeshesChanged(portal);
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
        CameraRenderState previous = camera;
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
                schedule(portal, portal.nextSection);
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
        boolean preparationPending = travel != null && travel.active && !travelTransition
            && !nativeTravelTerrain(travel) && !travelReady();
        int start = 0;
        for (int index = 0; index < buildDemand.size(); index++) {
            if (buildDemand.get(index).key == lastBuildPortal) {
                start = index + 1;
                break;
            }
        }
        for (int checked = 0; checked < buildDemand.size(); checked++) {
            Portal portal = buildDemand.get((start + checked) % buildDemand.size());
            if (nativeTravelTerrain(portal)) {
                continue;
            }
            if (portal == travelSource && preparationPending) {
                continue;
            }
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
        return context == null ? null : retainedMeshes.get(new RetainedMeshKey(context, key, portal.materials));
    }

    private void schedule(Portal portal, long key) {
        Minecraft minecraft = Minecraft.getInstance();
        if (portal.scene.empty(key)) {
            portal.dirty.remove(key);
            Section previous = portal.sections.put(key, new Section(key, portal.scene.revision(key)));
            if (previous != null) {
                closeSection(previous);
            }
            portal.orderDirty = true;
            retainedMeshesChanged(portal);
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
        BlockStateModelSet blockModels = models;
        FluidStateModelSet fluidModels = minecraft.getModelManager().getFluidStateModelSet();
        BlockColors colors = minecraft.getBlockColors();
        long revision = portal.scene.revision(key);
        int generation = portal.generation;
        boolean smoothLighting = ambientOcclusion;
        PortalTerrainMaterials materials = portal.materials;
        portal.dirty.remove(key);
        portal.building.add(key);
        pendingBuilds++;
        residentBuilds = portal.sections.containsKey(key) ? Math.min(3, residentBuilds + 1) : 0;
        lastBuildPortal = portal.key;
        CompletableFuture.supplyAsync(() -> PortalSectionMesh.compile(key, world, blockModels, fluidModels, colors, smoothLighting,
            materials), compiler)
            .whenComplete((mesh, failure) -> meshCompletions.add(new MeshCompletion(portal, key, revision, generation, identity, mesh, failure)));
    }

    private void finish(Portal portal, long key, long revision, int generation, PortalScene.MeshIdentity identity, PortalSectionMesh mesh, Throwable failure) {
        pendingBuilds--;
        portal.building.remove(key);
        long currentRevision = portal.scene.revision(key);
        Section displayed = portal.sections.get(key);
        if (!portal.active || nativeTravelTerrain(portal) || portal.generation != generation || currentRevision < 0
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
            retainedMeshesChanged(portal);
            if (previous != null) {
                closeSection(previous);
            }
            for (Map.Entry<ChunkSectionLayer, MeshData> entry : mesh.meshes().entrySet()) {
                PortalGpuMesh uploaded = new PortalGpuMesh(entry.getValue(), entry.getKey() == ChunkSectionLayer.TRANSLUCENT ? mesh.translucentSort() : null);
                section.layers.put(entry.getKey(), uploaded);
                gpuBytes += uploaded.bytes();
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
                    CameraRenderState current = camera;
                    camera = portal.contentCamera == null ? rootCamera : portal.contentCamera;
                    double candidate = distance(section.key);
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
            retainedMeshesChanged(owner);
            owner.evicted.add(farthest.key);
            closeSection(farthest);
        }
    }

    private boolean renderPortal(Portal portal, RenderDimensions dimensions) {
        prepareDestination(portal, dimensions);
        if (portal.destination != null && portal.shader == null) {
            try {
                maintain(portal);
            } finally {
                portal.environment.endFrame();
            }
            return false;
        }
        if (portal.shader != null) {
            renderShaderPortal(portal, dimensions);
            return true;
        }
        maintain(portal);
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice previousFog = RenderSystem.getShaderFog();
        ProjectionType projectionType = RenderSystem.getProjectionType();
        boolean reflected = portal.toRoot.determinant3x3() < 0;
        PipelineCache previousPipelines = reflected ? RenderSystem.setCurrentPipelineCache(pipelines.reflectedFeatures()) : null;
        PortalFeatureRenderer features = targets.features(dimensions.depth());
        try (PortalLightmapScope lightmap = new PortalLightmapScope(portal.environment.lightmap())) {
            features.prepare(portal.scene, camera, portal.cullFrustum);
            clearDestinationSky(portal, dimensions, projectionType);
            Matrix4f clippedProjection = clippedProjection(portal);
            RenderSystem.setProjectionMatrix(targets.projection(dimensions.depth()).getBuffer(clippedProjection), projectionType);
            RenderSystem.setShaderFog(portal.environment.fogBuffer());
            drawDestinationSolid(portal, features);
            drawNestedDestinations(portal, dimensions);
            RenderSystem.setProjectionMatrix(targets.projection(dimensions.depth()).getBuffer(clippedProjection), projectionType);
            RenderSystem.setShaderFog(portal.environment.fogBuffer());
            drawDestinationTranslucent(portal, features);
        } finally {
            clearTerrainTransforms(portal);
            try {
                features.closeFrame();
                portal.environment.endFrame();
            } finally {
                if (reflected) {
                    RenderSystem.setCurrentPipelineCache(previousPipelines);
                }
                RenderSystem.setProjectionMatrix(previousProjection, projectionType);
                RenderSystem.setShaderFog(previousFog);
                camera = portal.camera;
            }
        }
        return true;
    }

    private void renderShaderPortal(Portal portal, RenderDimensions dimensions) {
        renderShaderChildren(portal, dimensions);
        camera = portal.contentCamera;
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice previousFog = RenderSystem.getShaderFog();
        ProjectionType projectionType = RenderSystem.getProjectionType();
        Matrix4f projection = new Matrix4f(frameProjection);
        PortalShaderCamera shaderCamera = new PortalShaderCamera(portal.scene.environment(), camera);
        PortalShaderContext.View view = new PortalShaderContext.View(portal.scene.environment(), shaderCamera, portal.target,
            shaderCamera.getViewRotationMatrix(new Matrix4f()), projection);
        boolean reflected = portal.toRoot.determinant3x3() < 0;
        PipelineCache previousPipelines = reflected ? RenderSystem.setCurrentPipelineCache(pipelines.reflectedFeatures()) : null;
        PortalFeatureRenderer features = targets.features(dimensions.depth());
        try (PortalLightmapScope lightmap = new PortalLightmapScope(portal.environment.lightmap());
             PortalShaderRenderer.Frame frame = portal.shader.begin(view)) {
            maintain(portal);
            renderDestinationShadows(portal, features);
            camera = portal.contentCamera;
            portal.shader.prepare();
            RenderSystem.setProjectionMatrix(targets.projection(dimensions.depth()).getBuffer(frameProjection), projectionType);
            portal.environment.renderSky(portal.shader.sky());
            RenderSystem.getModelViewStack().set(camera.viewRotationMatrix);
            features.prepare(portal.scene, camera, portal.cullFrustum);
            RenderSystem.setProjectionMatrix(targets.projection(dimensions.depth()).getBuffer(projection), projectionType);
            RenderSystem.setShaderFog(portal.environment.fogBuffer());
            drawDestinationSolid(portal, features);
            portal.shader.translucents();
            drawDestinationTranslucent(portal, features);
            portal.shader.finish();
        } finally {
            clearTerrainTransforms(portal);
            try {
                features.closeFrame();
                portal.environment.endFrame();
            } finally {
                if (reflected) {
                    RenderSystem.setCurrentPipelineCache(previousPipelines);
                }
                RenderSystem.setProjectionMatrix(previousProjection, projectionType);
                RenderSystem.setShaderFog(previousFog);
                camera = portal.camera;
            }
        }
        compositeShaderChildren(portal);
    }

    private void renderDestinationShadows(Portal portal, PortalFeatureRenderer features) {
        boolean replacedSections = false;
        try (PortalShaderRenderer.ShadowFrame shadows = portal.shader.shadows(camera)) {
            if (shadows == null) {
                return;
            }
            camera = shadows.camera();
            replacedSections = true;
            portal.drawSections.clear();
            for (Section section : portal.sections.values()) {
                section.transform = null;
                if (camera.cullFrustum.isVisible(section.bounds)) {
                    portal.drawSections.add(section);
                }
            }
            try (PortalShaderRenderer.Frame phase = shadows.features()) {
                features.prepare(portal.scene, camera, shadows.entities(), shadows.blockEntities());
            }
            try (RenderPass pass = destinationPass(portal, Optional.empty())) {
                if (shadows.terrain()) {
                    drawTerrain(portal, ChunkSectionLayer.SOLID, pass);
                    drawTerrain(portal, ChunkSectionLayer.CUTOUT, pass);
                }
                try (PortalShaderRenderer.Frame phase = shadows.features()) {
                    features.executeSolid(pass);
                }
            }
            shadows.translucentDepth();
            try (RenderPass pass = destinationPass(portal, Optional.empty())) {
                if (shadows.translucent()) {
                    drawTerrain(portal, ChunkSectionLayer.TRANSLUCENT, pass);
                }
                try (PortalShaderRenderer.Frame phase = shadows.features()) {
                    features.executeTranslucent(pass);
                }
            }
        } finally {
            if (replacedSections) {
                clearTerrainTransforms(portal);
            }
            try {
                features.closeFrame();
            } finally {
                camera = portal.contentCamera;
                if (replacedSections) {
                    portal.drawSections.clear();
                    for (Section section : orderedSections(portal)) {
                        section.transform = null;
                        if (portal.cullFrustum.isVisible(section.bounds)) {
                            portal.drawSections.add(section);
                        }
                    }
                }
            }
        }
    }

    private RenderDimensions childDimensions(RenderDimensions parent) {
        int depth = parent.depth() + 1;
        if (shaderRenderer == null || depth >= PortalRenderTargets.DEPTHS) {
            return new RenderDimensions(parent.width(), parent.height(), depth);
        }
        PortalShaderRenderer.Resolution size = shaderSizes.get(depth);
        return new RenderDimensions(size.width(), size.height(), depth);
    }

    private PortalViewport childViewport(Portal portal, RenderDimensions dimensions) {
        RenderDimensions child = childDimensions(dimensions);
        return portal.viewport.rescale(dimensions.width(), dimensions.height(), child.width(), child.height());
    }

    private void renderShaderChildren(Portal portal, RenderDimensions dimensions) {
        Matrix4d childSpace = new Matrix4d(portal.contentToRoot)
            .mul(PortalProjection.destinationToSource(portal.scene.environment().transform()));
        for (Portal child : portals.values()) {
            if (child.scene.geometry().parentPortalKey() == portal.key) {
                renderTree(child, childSpace, childViewport(portal, dimensions),
                    childDimensions(dimensions));
            }
        }
        camera = portal.contentCamera;
    }

    private void compositeShaderChildren(Portal portal) {
        try (PortalShaderScope scope = PortalShaderScope.rendering();
             RenderPass pass = destinationPass(portal, Optional.empty())) {
            for (Portal child : portals.values()) {
                if (child.rendered && child.scene.geometry().parentPortalKey() == portal.key) {
                    composite(child, portal, pass);
                }
            }
        }
        camera = portal.contentCamera;
    }

    private void materialContext(Portal portal, PortalTerrainMaterials materials) {
        if (portal.materials.revision() == materials.revision() && portal.materials.enabled() == materials.enabled()) {
            return;
        }
        portal.generation++;
        portal.restoreSequence = 0L;
        retainedMeshesChanged(portal);
        for (Section section : portal.sections.values()) {
            if (!retainMesh(portal, section)) {
                closeSection(section);
            }
        }
        portal.materials = materials;
        portal.sections.clear();
        portal.sortedSections.clear();
        portal.drawSections.clear();
        portal.orderDirty = true;
        portal.evicted.clear();
        for (LongIterator sections = portal.scene.sectionKeys().iterator(); sections.hasNext();) {
            portal.dirty.add(sections.nextLong());
        }
    }

    private void prepareDestination(Portal portal, RenderDimensions dimensions) {
        if (portal.uniformWidth != dimensions.width() || portal.uniformHeight != dimensions.height()) {
            releaseTarget(portal);
            portal.uniformWidth = dimensions.width();
            portal.uniformHeight = dimensions.height();
        }
        if (shaderRenderer == null) {
            portal.destination = null;
            portal.shader = null;
            portal.target = portal.scene.fullWorld() ? targets.travel(portal.key, dimensions.width(), dimensions.height())
                : targets.scratch(dimensions.depth(), dimensions.width(), dimensions.height());
        } else {
            PortalShaderRenderer.Session session = shaderRenderer.acquire(portal.key, portal.scene.environment(), dimensions.width(), dimensions.height());
            portal.destination = session;
            portal.shader = session.ready() ? session : null;
            portal.target = session.target();
        }
        materialContext(portal, portal.destination == null ? PortalTerrainMaterials.VANILLA : portal.destination.materials());
        restoreRetainedMeshes(portal);
        if (portal.compositeUniform == null && (portal.destination == null || portal.shader != null)) {
            portal.compositeUniform = compositeUniform(new PortalViewport(0, 0,
                portal.scene.geometry().parentPortalKey() == 0 ? frameWidth : dimensions.width(),
                portal.scene.geometry().parentPortalKey() == 0 ? frameHeight : dimensions.height()));
        }
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
            section.transform = null;
            if (portal.cullFrustum.isVisible(section.bounds)) {
                portal.drawSections.add(section);
                PortalGpuMesh translucent = section.layers.get(ChunkSectionLayer.TRANSLUCENT);
                if (translucent != null) {
                    if (translucentSorting == null) {
                        translucentSorting = new ByteBufferBuilder(4096);
                    }
                    translucent.sort(translucentSorting, (float) (camera.pos.x - (SectionPos.x(section.key) << 4)),
                        (float) (camera.pos.y - (SectionPos.y(section.key) << 4)), (float) (camera.pos.z - (SectionPos.z(section.key) << 4)));
                }
            }
        }
    }

    private void clearDestinationSky(Portal portal, RenderDimensions dimensions, ProjectionType projectionType) {
        try (RenderPass clear = destinationPass(portal, Optional.of(portal.environment.fogData().color))) {
        }
        RenderSystem.setProjectionMatrix(targets.projection(dimensions.depth()).getBuffer(frameProjection), projectionType);
        portal.environment.renderSky(portal.scene.fullWorld() ? targets.travelSky(portal.key) : targets.sky(dimensions.depth()));
    }

    private Matrix4f clippedProjection(Portal portal) {
        if (portal.scene.fullWorld()) {
            return new Matrix4f(frameProjection);
        }
        return PortalProjection.clip(frameProjection, camera.viewRotationMatrix, cameraPlane(portal),
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
    }

    private Vector4f cameraPlane(Portal portal) {
        if (portal.scene.fullWorld()) {
            return new Vector4f(0.0f);
        }
        AperturePolygon.Plane plane = portal.aperture.plane();
        float side = portal.scene.geometry().frontSide() ? 1 : -1;
        return new Vector4f(side * (float) plane.x(), side * (float) plane.y(), side * (float) plane.z(),
            side * (float) plane.signedDistance(new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z)));
    }

    private PortalClipScope geometryClipping(Portal portal) {
        return portal.shader == null ? null : PortalClipScope.open(
            PortalProjection.clipDistance(frameProjection, camera.viewRotationMatrix, cameraPlane(portal)));
    }

    private void drawDestinationSolid(Portal portal, PortalFeatureRenderer features) {
        try (RenderPass pass = destinationPass(portal, Optional.empty());
             PortalClipScope clipping = geometryClipping(portal)) {
            drawTerrain(portal, ChunkSectionLayer.SOLID, pass);
            drawTerrain(portal, ChunkSectionLayer.CUTOUT, pass);
            features.executeSolid(pass);
        }
    }

    private void drawNestedDestinations(Portal portal, RenderDimensions dimensions) {
        Matrix4d childSpace = new Matrix4d(portal.contentToRoot)
            .mul(PortalProjection.destinationToSource(portal.scene.environment().transform()));
        for (Portal child : portals.values()) {
            if (child.scene.geometry().parentPortalKey() == portal.key && renderTree(child, childSpace, childViewport(portal, dimensions),
                childDimensions(dimensions))) {
                try (RenderPass pass = destinationPass(portal, Optional.empty())) {
                    composite(child, portal, pass);
                }
            }
            camera = portal.contentCamera;
        }
    }

    private void drawDestinationTranslucent(Portal portal, PortalFeatureRenderer features) {
        try (RenderPass pass = destinationPass(portal, Optional.empty())) {
            try (PortalClipScope clipping = geometryClipping(portal)) {
                drawTerrain(portal, ChunkSectionLayer.TRANSLUCENT, pass);
                features.executeTranslucent(pass);
            }
            portal.environment.renderClouds(pass);
        }
    }

    private RenderPass destinationPass(Portal portal, Optional<Vector4fc> clear) {
        PortalViewport viewport = portal.viewport;
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes destination",
            portal.target.getColorTextureView(), clear, portal.target.getDepthTextureView(),
            clear.isPresent() ? OptionalDouble.of(0.0) : OptionalDouble.empty(),
            new RenderPass.RenderArea(viewport.x(), viewport.y(), viewport.width(), viewport.height()));
    }

    private void drawTerrain(Portal portal, ChunkSectionLayer layer, RenderPass pass) {
        try {
            pass.setPipeline(portal.shader == null ? pipelines.terrain(layer, portal.toRoot.determinant3x3() < 0, portal.materials.enabled())
                : portal.shader.terrain(layer, portal.toRoot.determinant3x3() < 0));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Sampler0", Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
                portal.shader == null ? terrainSampler : PortalIrisTerrain.sampler(anisotropy));
            pass.setUniform("Sampler2", portal.environment.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            for (Section section : portal.drawSections) {
                PortalGpuMesh mesh = section.layers.get(layer);
                if (mesh == null || mainOwnsTravelSection(portal, section.key)) {
                    continue;
                }
                if (portal.shader == null && section.clip == null) {
                    AperturePolygon.Plane plane = portal.aperture.plane();
                    float side = portal.scene.geometry().frontSide() ? 1.0f : -1.0f;
                    section.clip = uniform(portal.scene.fullWorld() ? new Vector4f(0.0f) : new Vector4f(side * (float) plane.x(), side * (float) plane.y(), side * (float) plane.z(),
                        side * (float) (plane.offset() + plane.x() * (SectionPos.x(section.key) << 4)
                            + plane.y() * (SectionPos.y(section.key) << 4) + plane.z() * (SectionPos.z(section.key) << 4))), portal.viewport);
                }
                if (section.transform == null) {
                    section.transform = RenderSystem.getDynamicUniforms().writeTransform(portal.shader == null ? terrainTransform(camera, section.key)
                        : shaderTerrainTransform(camera, section.key));
                }
                pass.setUniform("DynamicTransforms", section.transform);
                if (portal.shader == null) {
                    pass.setUniform("Portal", section.clip);
                }
                mesh.draw(pass);
            }
        } finally {
            if (portal.shader != null) {
                portal.shader.endTerrain();
            }
        }
    }

    private static void clearTerrainTransforms(Portal portal) {
        for (Section section : portal.drawSections) {
            section.transform = null;
        }
    }

    private static PortalGpuMesh fullscreenMesh() {
        try (ByteBufferBuilder allocation = new ByteBufferBuilder(128)) {
            BufferBuilder builder = new BufferBuilder(allocation, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
            builder.addVertex(-1, -1, 0);
            builder.addVertex(1, -1, 0);
            builder.addVertex(1, 1, 0);
            builder.addVertex(-1, 1, 0);
            try (MeshData mesh = builder.buildOrThrow()) {
                return new PortalGpuMesh(mesh, null);
            }
        }
    }

    private void releaseFrameTargets() {
        if (translucentSorting != null) {
            translucentSorting.close();
            translucentSorting = null;
        }
        targets.close();
        if (nativeEnvironment != null) {
            nativeEnvironment.close();
            nativeEnvironment = null;
        }
        portalLayer = null;
        if (layerMesh != null) {
            layerMesh.close();
            layerMesh = null;
        }
    }

    private List<Section> orderedSections(Portal portal) {
        if (portal.orderDirty || !camera.pos.equals(portal.sortPosition)) {
            portal.sortedSections.clear();
            for (Section section : portal.sections.values()) {
                if (!section.layers.isEmpty()) {
                    section.sortDistance = distance(section.key);
                    portal.sortedSections.add(section);
                }
            }
            portal.sortedSections.sort(FARTHER_SECTION_FIRST);
            portal.sortPosition = camera.pos;
            portal.orderDirty = false;
        }
        return portal.sortedSections;
    }

    private static PortalGpuMesh apertureMesh(Portal portal) {
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
        return !portal.apertureReady || !portal.aperture.hasShape()
            || portal.apertureSubdivisions == PortalApertureMesh.subdivisions(requestedShapeSubdivisions(),
                portal.geometry.apertureWidth(), portal.geometry.apertureHeight()) && portal.apertureFeather == edgeFeather();
    }

    private static void closeAperture(Portal portal) {
        if (portal.apertureMesh != null) {
            portal.apertureMesh.close();
            portal.apertureMesh = null;
        }
        if (portal.featherUniform != null) {
            portal.featherUniform.close();
            portal.featherUniform = null;
        }
        portal.featherColor = null;
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

    private GpuBufferSlice transform(int x, int y, int z) {
        Matrix4f modelView = new Matrix4f(camera.viewRotationMatrix).translate((float) (x - camera.pos.x), (float) (y - camera.pos.y), (float) (z - camera.pos.z));
        return RenderSystem.getDynamicUniforms().writeTransform(modelView);
    }

    static DynamicGpuData.Transform terrainTransform(CameraRenderState camera, long sectionKey) {
        Vector3f offset = new Vector3f((float) ((SectionPos.x(sectionKey) << 4) - camera.pos.x),
            (float) ((SectionPos.y(sectionKey) << 4) - camera.pos.y), (float) ((SectionPos.z(sectionKey) << 4) - camera.pos.z));
        return new DynamicGpuData.Transform(camera.viewRotationMatrix, WHITE, offset, IDENTITY_TEXTURE);
    }

    static DynamicGpuData.Transform shaderTerrainTransform(CameraRenderState camera, long sectionKey) {
        Matrix4f modelView = new Matrix4f(camera.viewRotationMatrix).translate(
            (float) ((SectionPos.x(sectionKey) << 4) - camera.pos.x),
            (float) ((SectionPos.y(sectionKey) << 4) - camera.pos.y),
            (float) ((SectionPos.z(sectionKey) << 4) - camera.pos.z));
        return new DynamicGpuData.Transform(modelView, WHITE, new Vector3f(), IDENTITY_TEXTURE);
    }

    static GpuBuffer compositeUniform(PortalViewport viewport) {
        return uniform(new Vector4f(0.0f), viewport);
    }

    private static GpuBuffer uniform(Vector4f plane, PortalViewport viewport) {
        ByteBuffer data = MemoryUtil.memAlloc(48);
        try {
            data.putFloat(plane.x).putFloat(plane.y).putFloat(plane.z).putFloat(plane.w);
            data.putFloat(viewport.width()).putFloat(viewport.height()).putFloat(viewport.x()).putFloat(viewport.y());
            boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
            data.putFloat(zeroToOne ? 1.0f : 0.5f).putFloat(zeroToOne ? 0.0f : 0.5f).putFloat(0.0f).putFloat(0.0f).flip();
            return RenderSystem.getDevice().createBuffer(() -> "Portal parameters", GpuBuffer.USAGE_UNIFORM, data);
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    private static GpuSampler sampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }

    private double distance(long key) {
        double x = (SectionPos.x(key) << 4) + 8.0 - camera.pos.x;
        double y = (SectionPos.y(key) << 4) + 8.0 - camera.pos.y;
        double z = (SectionPos.z(key) << 4) + 8.0 - camera.pos.z;
        return x * x + y * y + z * z;
    }

    private boolean visiblePortal(Portal portal, Vec3d eye) {
        return camera.cullFrustum == null || Math.abs(portal.aperture.plane().signedDistance(eye)) < 0.2
            || camera.cullFrustum.isVisible(portal.bounds);
    }

    private boolean inTravelFrustum(Portal portal, long key) {
        if (portal.cullFrustum == null) {
            return true;
        }
        int x = SectionPos.x(key) << 4;
        int y = SectionPos.y(key) << 4;
        int z = SectionPos.z(key) << 4;
        return portal.cullFrustum.isVisible(new AABB(x - 1, y - 1, z - 1, x + 17, y + 17, z + 17));
    }

    private boolean visibleSection(Portal portal, long key) {
        if (portal == travelSource || portal == travel && !travelTransition) {
            return true;
        }
        int x = SectionPos.x(key) << 4;
        int y = SectionPos.y(key) << 4;
        int z = SectionPos.z(key) << 4;
        return portal.cullFrustum.isVisible(new AABB(x - 1, y - 1, z - 1, x + 17, y + 17, z + 17));
    }

    private boolean nativeTravelTerrain(Portal portal) {
        if (portal == travelSource) {
            Portal destination = travel == null ? arrival : travel;
            return destination != null && nativeTravelTerrain(destination);
        }
        return (portal == travel || portal == arrival) && portal.scene instanceof ClientTravelScene scene
            && ClientSodiumTerrain.usesPreparedTerrain(scene.level());
    }

    private void release(Portal portal) {
        retainedMeshesChanged(portal);
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

    private void retainedMeshesChanged(Portal portal) {
        if (portal == travel) {
            travelMeshEpoch++;
        }
    }

    private void fail(Portal portal, Failure kind, Throwable failure) {
        if (!portal.active) {
            return;
        }
        if (portal.failure != kind) {
            LOGGER.error("Native portal {} is unavailable after {} failure; retrying native rendering in five seconds", portal.key, kind, failure);
        }
        portal.failure = kind;
        portal.active = false;
        portal.retryAt = System.nanoTime() + RETRY_NANOS;
        portal.generation++;
        try {
            release(portal);
        } finally {
            portal.shader = null;
            if (shaderRenderer != null) {
                try {
                    shaderRenderer.discard(portal.key);
                } catch (RuntimeException cleanup) {
                    LOGGER.error("Unable to release failed native portal shader {}", portal.key, cleanup);
                }
            }
        }
    }

    void retryUnavailable(long now) {
        for (Portal portal : portals.values()) {
            retry(portal, now);
        }
    }

    private static void retry(Portal portal, long now) {
        if (!portal.active && portal.failure != null && now - portal.retryAt >= 0L) {
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
        portal.destination = null;
        if (portal.compositeUniform != null) {
            portal.compositeUniform.close();
            portal.compositeUniform = null;
        }
        if (portal.parentClip != null) {
            portal.parentClip.close();
            portal.parentClip = null;
        }
    }

    private boolean retainMesh(Portal portal, Section section) {
        section.transform = null;
        if (section.identity == null || portal.failure != null || portal.dirty.contains(section.key)
            || portal.building.contains(section.key) || section.revision != portal.scene.revision(section.key)) {
            return false;
        }
        RetainedMeshKey key = new RetainedMeshKey(section.identity, section.key, portal.materials);
        Section previous = removeRetainedMesh(key);
        if (previous != null) {
            removeMeshProof(previous.identity);
            closeSection(previous);
        }
        if (section.clip != null) {
            section.clip.close();
            section.clip = null;
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
        removeRetainedMesh(new RetainedMeshKey(cached.identity, sectionKey, portal.materials));
        if (!matches) {
            removeMeshProof(cached.identity);
            closeSection(cached);
            return false;
        }
        installRetainedMesh(portal, cached, portal.scene.revision(sectionKey));
        return true;
    }

    private void restoreRetainedMeshes(Portal portal) {
        if (buildBudgetNanos <= 0L || retainedMeshes.isEmpty() || !portal.active || portal.failure != null
            || nativeTravelTerrain(portal)) {
            return;
        }
        PortalScene.MeshIdentity context = portal.scene.meshContext();
        if (context == null) {
            return;
        }
        long started = System.nanoTime();
        long slice = pendingBuilds < 2 ? buildBudgetNanos / 2 : buildBudgetNanos;
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
        return portal.materials.equals(key.materials) && context.sameContext(key.identity)
            && !portal.sections.containsKey(key.section) && !portal.building.contains(key.section)
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
        retainedMeshesChanged(portal);
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

    private record RetainedMeshKey(PortalScene.MeshIdentity identity, long section, PortalTerrainMaterials materials) {
        @Override
        public int hashCode() {
            return 31 * (31 * identity.contextHash() + Long.hashCode(section)) + materials.hashCode();
        }

        @Override
        public boolean equals(Object value) {
            return this == value || value instanceof RetainedMeshKey other && section == other.section
                && materials.equals(other.materials) && identity.sameContext(other.identity);
        }
    }

    private void closeSection(Section section) {
        section.transform = null;
        for (PortalGpuMesh mesh : section.layers.values()) {
            gpuBytes -= mesh.bytes();
            mesh.close();
        }
        if (section.clip != null) {
            section.clip.close();
        }
    }

    private record MeshCompletion(Portal portal, long key, long revision, int generation, PortalScene.MeshIdentity identity, PortalSectionMesh mesh, Throwable failure) {
    }

    private record RenderDimensions(int width, int height, int depth) {
    }

    private enum Failure {
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
        private Vec3 sortPosition;
        private boolean orderDirty = true;
        private CameraRenderState camera;
        private CameraRenderState contentCamera;
        private Frustum cullFrustum;
        private boolean rendered;
        private boolean rendering;
        private GpuBuffer parentClip;
        private int parentClipWidth;
        private int parentClipHeight;
        private ApertureDescriptor parentClipGeometry;
        private final Matrix4d parentClipToRoot = new Matrix4d();
        private PortalViewport viewport;
        private int uniformWidth;
        private int uniformHeight;
        private AperturePolygon aperture;
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
        private long retryAt;
        private int generation;
        private PortalShaderRenderer.Session shader;
        private PortalShaderRenderer.Session destination;
        private PortalTerrainMaterials materials = PortalTerrainMaterials.VANILLA;
        private TextureTarget target;
        private PortalEnvironmentRenderer environment;
        private PortalGpuMesh apertureMesh;
        private boolean apertureReady;
        private ShapeMesh apertureShape;
        private int apertureSubdivisions;
        private float apertureFeather;
        private GpuBuffer featherUniform;
        private EnvironmentState.Color featherColor;
        private float featherWidth;
        private GpuBuffer compositeUniform;

        private Portal(int key, PortalScene scene) {
            this.key = key;
            this.scene = scene;
            updateGeometry();
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
        private final EnumMap<ChunkSectionLayer, PortalGpuMesh> layers = new EnumMap<>(ChunkSectionLayer.class);
        private GpuBuffer clip;
        private GpuBufferSlice transform;

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
