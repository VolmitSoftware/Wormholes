package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.render.client.ClientPortalAperture;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
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
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClientPortalRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_GPU_BYTES = 128L * 1024L * 1024L;
    static final long RETRY_NANOS = 5_000_000_000L;
    private static final Vector4fc WHITE = new Vector4f(1.0f);
    private static final Matrix4fc IDENTITY_TEXTURE = new Matrix4f();
    private static final ClientPortalRenderer INSTANCE = new ClientPortalRenderer();
    private final Int2ObjectOpenHashMap<Portal> portals = new Int2ObjectOpenHashMap<>();
    private final ExecutorService compiler = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Wormholes portal mesher").factory());
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
    private int pendingBuilds;
    private int residentBuilds;
    private int lastBuildPortal;
    private long gpuBytes;
    private GpuSampler terrainSampler;
    private boolean rgss;
    private int anisotropy;
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

    public boolean available(int portalKey) {
        Portal portal = portals.get(portalKey);
        if (portal == null) {
            return false;
        }
        retry(portal, System.nanoTime());
        return portal.active;
    }

    public boolean coversEndPortalSurface(BlockPos position) {
        for (Portal portal : portals.values()) {
            ClientPortalGeometry geometry = portal.scene.geometry();
            if (portal.active && portal.rendered && geometry.parentPortalKey() == 0
                && geometry.kind() == ClientPortalGeometry.KIND_VANILLA_REPLACEMENT
                && geometry.facingDirection().y() != 0 && position.getY() == geometry.originY()
                && geometry.apertureOpen(position.getX() - geometry.originX(), position.getZ() - geometry.originZ())) {
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

    public void refreshScene(int portalKey, PortalScene scene) {
        Portal portal = portals.get(portalKey);
        if (portal == null) {
            replaceScene(portalKey, scene);
            return;
        }
        boolean changed = !portal.geometry.sameSurface(scene.geometry());
        portal.scene = scene;
        if (!changed) {
            return;
        }
        portal.updateGeometry();
        if (portal.apertureMesh != null) {
            portal.apertureMesh.close();
            portal.apertureMesh = null;
        }
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
        if (shaderRenderer != null) {
            shaderRenderer.resetHistory(portalKey);
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
        visible.clear();
        buildDemand.clear();
        residentBuilds = 0;
        lastBuildPortal = 0;
        if (pipelines != null) {
            pipelines.close();
            pipelines = null;
        }
        if (terrainSampler != null) {
            terrainSampler.close();
            terrainSampler = null;
        }
    }

    public void invalidate(int portalKey, long sectionKey) {
        Portal portal = portals.get(portalKey);
        if (portal != null) {
            portal.evicted.remove(sectionKey);
            portal.dirty.add(sectionKey);
        }
    }

    public void resourceReload() {
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
        if (pipelines != null) {
            pipelines.close();
            pipelines = null;
        }
        models = null;
    }

    public void prepare(CameraRenderState camera, GpuBufferSlice fog) {
        if (PortalShaderScope.shadowPass()) {
            return;
        }
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
        Minecraft minecraft = Minecraft.getInstance();
        BlockStateModelSet currentModels = minecraft.getModelManager().getBlockStateModelSet();
        boolean shaders = PortalShaderScope.shaders();
        boolean currentAmbientOcclusion = minecraft.options.ambientOcclusion().get();
        if (models != currentModels || currentAmbientOcclusion != ambientOcclusion || shaders != (shaderRenderer != null)) {
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
            shaderRenderer.beginFrame();
            collectShaderDemand();
            shaderSizes = shaderRenderer.resolution(new PortalShaderRenderer.Sizing(main.width, main.height, shaderDemand));
            PortalShaderRenderer.Resolution resolution = shaderSizes.getFirst();
            dimensions = new RenderDimensions(resolution.width(), resolution.height(), 0);
            prewarmShaders(dimensions);
        }
        portalLayer = targets.layer(main.width, main.height);
        try (RenderPass clear = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes portal layer clear",
            portalLayer.getColorTextureView(), Optional.of(new Vector4f(0.0f)), portalLayer.getDepthTextureView(), OptionalDouble.of(0.0))) {
        }
        if (layerMesh == null) {
            layerMesh = fullscreenMesh();
        }
        for (Portal portal : portals.values()) {
            portal.rendered = false;
            portal.rendering = false;
            portal.target = null;
        }
        for (Portal portal : portals.values()) {
            if (portal.scene.geometry().parentPortalKey() == 0 && renderTree(portal, new Matrix4d(), null, dimensions)) {
                visible.add(portal);
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes aperture layer",
                    portalLayer.getColorTextureView(), Optional.empty(), portalLayer.getDepthTextureView(), OptionalDouble.empty())) {
                    composite(portal, null, pass);
                }
            }
        }
        dispatchBuilds();
        for (Portal portal : portals.values()) {
            if (!portal.rendered) {
                releaseTarget(portal);
            }
        }
        targets.endFrame();
        this.camera = rootCamera;
    }

    private void collectShaderDemand() {
        shaderDemand.clear();
        shaderRoots.clear();
        ClientPortalAperture.Point eye = new ClientPortalAperture.Point(rootCamera.pos.x, rootCamera.pos.y, rootCamera.pos.z);
        Matrix4d viewProjection = new Matrix4d(frameProjection).mul(new Matrix4d(rootCamera.viewRotationMatrix))
            .translate(-rootCamera.pos.x, -rootCamera.pos.y, -rootCamera.pos.z);
        boolean zeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
        for (Portal portal : portals.values()) {
            if (portal.scene.geometry().parentPortalKey() == 0 && portal.active && portal.scene.environment() != null
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
            if (portal.scene.geometry().parentPortalKey() != 0 || !portal.active || portal.scene.environment() == null
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
            CameraRenderState display = transformedCamera(rootCamera, toRoot, frameProjection);
            camera = display;
            portal.camera = display;
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
            Matrix4d childSpace = new Matrix4d(toRoot).mul(PortalProjection.destinationToSource(portal.scene.environment().transform()));
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
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try (PortalFramebufferScope framebuffer = PortalFramebufferScope.capture();
             RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Wormholes shader apertures",
                 main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty())) {
            compositeLayer(pass);
        }
    }

    private void compositeLayer(RenderPass pass) {
        try {
            if (visible.isEmpty() || portalLayer == null || layerMesh == null) {
                return;
            }
            try (PortalShaderScope scope = PortalShaderScope.rendering()) {
                pass.setPipeline(pipelines.layer());
                pass.setUniform("Sampler0", portalLayer.getColorTextureView(), sampler());
                pass.setUniform("Sampler1", portalLayer.getDepthTextureView(), sampler());
                layerMesh.draw(pass);
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
        if (!portal.active || portal.scene.environment() == null || portal.rendering || portal.rendered || dimensions.depth() >= PortalRenderTargets.DEPTHS) {
            return false;
        }
        if (!portal.toRoot.equals(toRoot) && portal.parentClip != null) {
            portal.parentClip.close();
            portal.parentClip = null;
        }
        portal.toRoot.set(toRoot);
        portal.camera = transformedCamera(rootCamera, toRoot, frameProjection);
        camera = portal.camera;
        ClientPortalAperture.Point eye = new ClientPortalAperture.Point(camera.pos.x, camera.pos.y, camera.pos.z);
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
        portal.cullFrustum = viewport.frustum(camera, frameProjection, dimensions.width(), dimensions.height());
        portal.rendering = true;
        renderPortal(portal, dimensions);
        portal.rendering = false;
        portal.rendered = true;
        recovered(portal, Failure.FRAME);
        return true;
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
        result.orientation = new Quaternionf().setFromNormalized(billboardView.invert());
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
        pass.setPipeline(pipelines.composite());
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transform(portal.scene.geometry().originX(), portal.scene.geometry().originY(), portal.scene.geometry().originZ()));
        if (parent == null) {
            pass.setUniform("Portal", portal.compositeUniform);
        } else {
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
                ClientPortalAperture.Plane plane = parent.aperture.plane();
                float side = parent.scene.geometry().frontSide() ? 1 : -1;
                Matrix4d childToParent = new Matrix4d(parent.toRoot).invert().mul(portal.toRoot);
                Vector4d transformed = childToParent.transpose().transform(new Vector4d(plane.x(), plane.y(), plane.z(), plane.offset()));
                double offset = transformed.w + transformed.x * portal.scene.geometry().originX()
                    + transformed.y * portal.scene.geometry().originY() + transformed.z * portal.scene.geometry().originZ();
                portal.parentClip = uniform(new Vector4f(side * (float) transformed.x, side * (float) transformed.y,
                    side * (float) transformed.z, side * (float) offset), new PortalViewport(0, 0, parent.target.width, parent.target.height));
            }
            pass.setUniform("Portal", portal.parentClip);
        }
        pass.setUniform("Sampler0", portal.target.getColorTextureView(), sampler());
        portal.apertureMesh.draw(pass);
    }

    private void maintain(Portal portal) {
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
            if (portal.scene.revision(key) < 0) {
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
                iterator.remove();
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
        CameraRenderState previous = camera;
        try {
            while (pendingBuilds < 2) {
                boolean resident = residentBuilds < 3;
                Portal portal = nextBuildPortal(resident);
                if (portal == null) {
                    resident = !resident;
                    portal = nextBuildPortal(resident);
                }
                if (portal == null) {
                    return;
                }
                camera = portal.camera;
                portal.nextSection = resident ? portal.residentSection : portal.initialSection;
                schedule(portal, portal.nextSection);
                selectNextSection(portal);
            }
        } finally {
            camera = previous;
        }
    }

    private Portal nextBuildPortal(boolean resident) {
        int start = 0;
        for (int index = 0; index < buildDemand.size(); index++) {
            if (buildDemand.get(index).key == lastBuildPortal) {
                start = index + 1;
                break;
            }
        }
        for (int checked = 0; checked < buildDemand.size(); checked++) {
            Portal portal = buildDemand.get((start + checked) % buildDemand.size());
            if (portal.active && portal.rendered && (resident ? portal.hasResidentBuild : portal.hasInitialBuild)) {
                return portal;
            }
        }
        return null;
    }

    private void schedule(Portal portal, long key) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockAndTintGetter world = portal.scene.world(key);
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
            .whenComplete((mesh, failure) -> minecraft.execute(() -> finish(portal, key, revision, generation, mesh, failure)));
    }

    private void finish(Portal portal, long key, long revision, int generation, PortalSectionMesh mesh, Throwable failure) {
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
            for (Map.Entry<ChunkSectionLayer, MeshData> entry : mesh.meshes().entrySet()) {
                PortalGpuMesh uploaded = new PortalGpuMesh(entry.getValue(), entry.getKey() == ChunkSectionLayer.TRANSLUCENT ? mesh.translucentSort() : null);
                section.layers.put(entry.getKey(), uploaded);
                gpuBytes += uploaded.bytes();
            }
            trim();
            recovered(portal, Failure.SECTION);
        } catch (RuntimeException uploadFailure) {
            fail(portal, Failure.SECTION, uploadFailure);
        }
    }

    private void trim() {
        while (gpuBytes > MAX_GPU_BYTES) {
            Portal owner = null;
            Section farthest = null;
            double distance = -1.0;
            for (Portal portal : portals.values()) {
                for (Section section : portal.sections.values()) {
                    CameraRenderState current = camera;
                    camera = portal.camera == null ? rootCamera : portal.camera;
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
            owner.evicted.add(farthest.key);
            closeSection(farthest);
        }
    }

    private void renderPortal(Portal portal, RenderDimensions dimensions) {
        prepareDestination(portal, dimensions);
        if (portal.shader != null && portal.shader.ready()) {
            renderShaderPortal(portal, dimensions);
            return;
        }
        materialContext(portal, PortalTerrainMaterials.VANILLA);
        maintain(portal);
        GpuBufferSlice previousProjection = RenderSystem.getProjectionMatrixBuffer();
        GpuBufferSlice previousFog = RenderSystem.getShaderFog();
        ProjectionType projectionType = RenderSystem.getProjectionType();
        boolean reflected = portal.toRoot.determinant3x3() < 0;
        PipelineCache previousPipelines = reflected ? RenderSystem.setCurrentPipelineCache(pipelines.reflectedFeatures()) : null;
        PortalFeatureRenderer features = targets.features(dimensions.depth());
        try (PortalLightmapScope lightmap = new PortalLightmapScope(portal.environment.lightmap())) {
            features.prepare(portal.scene, camera);
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
    }

    private void renderShaderPortal(Portal portal, RenderDimensions dimensions) {
        renderShaderChildren(portal, dimensions);
        camera = portal.camera;
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
            materialContext(portal, portal.shader.materials());
            maintain(portal);
            renderDestinationShadows(portal, features);
            camera = portal.camera;
            portal.shader.prepare();
            RenderSystem.setProjectionMatrix(targets.projection(dimensions.depth()).getBuffer(frameProjection), projectionType);
            portal.environment.renderSky(portal.shader.sky());
            RenderSystem.getModelViewStack().set(camera.viewRotationMatrix);
            features.prepare(portal.scene, camera);
            RenderSystem.setProjectionMatrix(targets.projection(dimensions.depth()).getBuffer(projection), projectionType);
            RenderSystem.setShaderFog(portal.environment.fogBuffer());
            drawDestinationSolid(portal, features);
            portal.shader.translucents();
            drawDestinationTranslucent(portal, features);
            portal.shader.finish();
        } finally {
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
        try (PortalShaderRenderer.ShadowFrame shadows = portal.shader.shadows(camera)) {
            if (shadows == null) {
                return;
            }
            camera = shadows.camera();
            portal.drawSections.clear();
            for (Section section : portal.sections.values()) {
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
            features.closeFrame();
            camera = portal.camera;
            portal.drawSections.clear();
            for (Section section : orderedSections(portal)) {
                if (portal.cullFrustum.isVisible(section.bounds)) {
                    portal.drawSections.add(section);
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
        Matrix4d childSpace = new Matrix4d(portal.toRoot)
            .mul(PortalProjection.destinationToSource(portal.scene.environment().transform()));
        for (Portal child : portals.values()) {
            if (child.scene.geometry().parentPortalKey() == portal.key) {
                renderTree(child, childSpace, childViewport(portal, dimensions),
                    childDimensions(dimensions));
            }
        }
        camera = portal.camera;
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
        camera = portal.camera;
    }

    private void materialContext(Portal portal, PortalTerrainMaterials materials) {
        if (portal.materials.revision() == materials.revision() && portal.materials.enabled() == materials.enabled()) {
            return;
        }
        portal.materials = materials;
        portal.generation++;
        for (Section section : portal.sections.values()) {
            closeSection(section);
        }
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
            portal.target = targets.scratch(dimensions.depth(), dimensions.width(), dimensions.height());
        } else {
            PortalShaderRenderer.Session session = shaderRenderer.acquire(portal.key, portal.scene.environment(), dimensions.width(), dimensions.height());
            portal.destination = session;
            portal.shader = session.ready() ? session : null;
            portal.target = session.target();
        }
        if (portal.compositeUniform == null) {
            portal.compositeUniform = compositeUniform(new PortalViewport(0, 0,
                portal.scene.geometry().parentPortalKey() == 0 ? frameWidth : dimensions.width(),
                portal.scene.geometry().parentPortalKey() == 0 ? frameHeight : dimensions.height()));
        }
        if (portal.apertureMesh == null) {
            portal.apertureMesh = apertureMesh(portal);
        }
        if (portal.environment == null) {
            portal.environment = new PortalEnvironmentRenderer();
        }
        portal.environment.prepare(portal.scene.environment(), camera);
        portal.drawSections.clear();
        for (Section section : orderedSections(portal)) {
            if (portal.cullFrustum.isVisible(section.bounds)) {
                portal.drawSections.add(section);
                PortalGpuMesh translucent = section.layers.get(ChunkSectionLayer.TRANSLUCENT);
                if (translucent != null) {
                    translucent.sort((float) (camera.pos.x - (SectionPos.x(section.key) << 4)),
                        (float) (camera.pos.y - (SectionPos.y(section.key) << 4)), (float) (camera.pos.z - (SectionPos.z(section.key) << 4)));
                }
            }
        }
    }

    private void clearDestinationSky(Portal portal, RenderDimensions dimensions, ProjectionType projectionType) {
        try (RenderPass clear = destinationPass(portal, Optional.of(portal.environment.fogData().color))) {
        }
        RenderSystem.setProjectionMatrix(targets.projection(dimensions.depth()).getBuffer(frameProjection), projectionType);
        portal.environment.renderSky(destinationSky(portal, dimensions));
    }

    private SkyRenderer destinationSky(Portal portal, RenderDimensions dimensions) {
        return portal.destination == null ? targets.sky(dimensions.depth()) : portal.destination.sky();
    }

    private Matrix4f clippedProjection(Portal portal) {
        return PortalProjection.clip(frameProjection, camera.viewRotationMatrix, cameraPlane(portal),
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
    }

    private Vector4f cameraPlane(Portal portal) {
        ClientPortalAperture.Plane plane = portal.aperture.plane();
        float side = portal.scene.geometry().frontSide() ? 1 : -1;
        return new Vector4f(side * (float) plane.x(), side * (float) plane.y(), side * (float) plane.z(),
            side * (float) plane.signedDistance(new ClientPortalAperture.Point(camera.pos.x, camera.pos.y, camera.pos.z)));
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
        Matrix4d childSpace = new Matrix4d(portal.toRoot)
            .mul(PortalProjection.destinationToSource(portal.scene.environment().transform()));
        for (Portal child : portals.values()) {
            if (child.scene.geometry().parentPortalKey() == portal.key && renderTree(child, childSpace, childViewport(portal, dimensions),
                childDimensions(dimensions))) {
                try (RenderPass pass = destinationPass(portal, Optional.empty())) {
                    composite(child, portal, pass);
                }
            }
            camera = portal.camera;
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
                if (mesh == null) {
                    continue;
                }
                if (section.clip == null) {
                    ClientPortalAperture.Plane plane = portal.aperture.plane();
                    float side = portal.scene.geometry().frontSide() ? 1.0f : -1.0f;
                    section.clip = uniform(new Vector4f(side * (float) plane.x(), side * (float) plane.y(), side * (float) plane.z(),
                        side * (float) (plane.offset() + plane.x() * (SectionPos.x(section.key) << 4)
                            + plane.y() * (SectionPos.y(section.key) << 4) + plane.z() * (SectionPos.z(section.key) << 4))), portal.viewport);
                }
                pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(portal.shader == null ? terrainTransform(camera, section.key)
                    : shaderTerrainTransform(camera, section.key)));
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
        targets.close();
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
                    portal.sortedSections.add(section);
                }
            }
            portal.sortedSections.sort(Comparator.comparingDouble((Section section) -> distance(section.key)).reversed());
            portal.sortPosition = camera.pos;
            portal.orderDirty = false;
        }
        return portal.sortedSections;
    }

    private PortalGpuMesh apertureMesh(Portal portal) {
        try (ByteBufferBuilder allocation = new ByteBufferBuilder(1024)) {
            BufferBuilder builder = new BufferBuilder(allocation, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
            for (ClientPortalAperture.Rectangle rectangle : portal.aperture.rectangles()) {
                for (ClientPortalAperture.Point point : portal.aperture.vertices(rectangle)) {
                    builder.addVertex((float) (point.x() - portal.scene.geometry().originX()),
                        (float) (point.y() - portal.scene.geometry().originY()), (float) (point.z() - portal.scene.geometry().originZ()));
                }
            }
            try (MeshData mesh = builder.buildOrThrow()) {
                return new PortalGpuMesh(mesh, null);
            }
        }
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

    private boolean visiblePortal(Portal portal, ClientPortalAperture.Point eye) {
        return camera.cullFrustum == null || Math.abs(portal.aperture.plane().signedDistance(eye)) < 0.2
            || camera.cullFrustum.isVisible(portal.bounds);
    }

    private boolean visibleSection(Portal portal, long key) {
        int x = SectionPos.x(key) << 4;
        int y = SectionPos.y(key) << 4;
        int z = SectionPos.z(key) << 4;
        return portal.cullFrustum.isVisible(new AABB(x - 1, y - 1, z - 1, x + 17, y + 17, z + 17));
    }

    private void release(Portal portal) {
        for (Section section : portal.sections.values()) {
            closeSection(section);
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
        if (portal.apertureMesh != null) {
            portal.apertureMesh.close();
            portal.apertureMesh = null;
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

    private void closeSection(Section section) {
        for (PortalGpuMesh mesh : section.layers.values()) {
            gpuBytes -= mesh.bytes();
            mesh.close();
        }
        if (section.clip != null) {
            section.clip.close();
        }
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
        private ClientPortalGeometry geometry;
        private final Matrix4d toRoot = new Matrix4d();
        private final List<Section> drawSections = new ArrayList<>();
        private final List<Section> sortedSections = new ArrayList<>();
        private Vec3 sortPosition;
        private boolean orderDirty = true;
        private CameraRenderState camera;
        private Frustum cullFrustum;
        private boolean rendered;
        private boolean rendering;
        private GpuBuffer parentClip;
        private int parentClipWidth;
        private int parentClipHeight;
        private ClientPortalGeometry parentClipGeometry;
        private final Matrix4d parentClipToRoot = new Matrix4d();
        private PortalViewport viewport;
        private int uniformWidth;
        private int uniformHeight;
        private ClientPortalAperture aperture;
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
        private GpuBuffer compositeUniform;

        private Portal(int key, PortalScene scene) {
            this.key = key;
            this.scene = scene;
            updateGeometry();
        }

        private void updateGeometry() {
            geometry = scene.geometry();
            aperture = ClientPortalAperture.from(geometry);
            ClientPortalAperture.Point min = aperture.point(0, 0);
            ClientPortalAperture.Point max = aperture.point(scene.geometry().apertureWidth(), scene.geometry().apertureHeight());
            bounds = new AABB(min.x(), min.y(), min.z(), max.x(), max.y(), max.z()).inflate(0.01);
        }
    }

    private static final class Section {
        private final long key;
        private final long revision;
        private final AABB bounds;
        private final EnumMap<ChunkSectionLayer, PortalGpuMesh> layers = new EnumMap<>(ChunkSectionLayer.class);
        private GpuBuffer clip;

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
