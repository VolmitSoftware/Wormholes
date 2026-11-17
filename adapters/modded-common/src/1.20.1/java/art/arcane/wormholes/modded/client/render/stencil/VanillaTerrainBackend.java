/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: VisibleSectionDiscovery reduced to a synchronous frustum and clip plane sweep over the 1.20.1
 * view area, standing in for LevelRenderer.setupRender inside portal layers.
 */
package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.mixin.client.VanillaTerrainRendererAccess;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4dc;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Vanilla terrain inside portal layers. Each layer gets its own {@code renderChunksInFrustum} list, filled by a sweep of
 * the renderer's view area against the layer frustum and the portal clip plane instead of vanilla's occlusion search,
 * which would start from a camera that may sit inside solid blocks behind the destination portal. Vanilla's
 * {@code compileChunks} then runs on that list, so sections only visible through a portal still compile.
 */
public final class VanillaTerrainBackend implements TerrainBackend {
    public static final VanillaTerrainBackend INSTANCE = new VanillaTerrainBackend();
    private static final MethodHandle CHUNK_INFO = chunkInfoFactory();

    private final Deque<Preparation> layers = new ArrayDeque<>();
    private final List<ObjectArrayList<Object>> lists = new ArrayList<>();
    private final Map<ViewArea, Map<ChunkRenderDispatcher.RenderChunk, Object>> infos = new WeakHashMap<>();
    private final Map<LevelRenderer, Long> cameraSections = new WeakHashMap<>();

    private VanillaTerrainBackend() {
    }

    @Override
    public String name() {
        return "vanilla";
    }

    @Override
    public boolean clipsTerrain() {
        return true;
    }

    @Override
    public void beginLayer(PortalLayer layer) {
        VanillaTerrainRendererAccess access = (VanillaTerrainRendererAccess) layer.renderer();
        ObjectArrayList<Object> visible = list(layer.depth());
        visible.clear();
        Preparation preparation = new Preparation(layer, access.wormholes$visibleChunks(), access.wormholes$transparentX(),
            access.wormholes$transparentY(), access.wormholes$transparentZ(), visible);
        access.wormholes$visibleChunks(visible);
        if (layer.shared()) {
            Vec3 camera = layer.camera().pos();
            access.wormholes$transparentX(camera.x);
            access.wormholes$transparentY(camera.y);
            access.wormholes$transparentZ(camera.z);
        }
        layers.push(preparation);
    }

    @Override
    public void endLayer(PortalLayer layer) {
        Preparation preparation = layers.peek();
        if (preparation == null || preparation.layer != layer) {
            throw new IllegalStateException("Vanilla terrain layer preparation is unbalanced");
        }
        layers.pop();
        VanillaTerrainRendererAccess access = (VanillaTerrainRendererAccess) layer.renderer();
        access.wormholes$visibleChunks(preparation.previous);
        if (layer.shared()) {
            access.wormholes$transparentX(preparation.transparentX);
            access.wormholes$transparentY(preparation.transparentY);
            access.wormholes$transparentZ(preparation.transparentZ);
        }
        preparation.visible.clear();
    }

    /**
     * Replaces {@code setupRender} for the layer drawing with {@code renderer}. Returns false outside a layer, so the
     * caller runs vanilla's own setup.
     */
    public boolean setupLayer(LevelRenderer renderer, Camera camera) {
        Preparation preparation = layers.peek();
        if (preparation == null || preparation.layer.renderer() != renderer) {
            return false;
        }
        VanillaTerrainRendererAccess access = (VanillaTerrainRendererAccess) renderer;
        PortalLayer layer = preparation.layer;
        Vec3 position = camera.getPosition();
        if (!layer.shared()) {
            if (Minecraft.getInstance().options.getEffectiveRenderDistance() != (int) renderer.getLastViewDistance()) {
                renderer.allChanged();
            }
            ViewArea area = access.wormholes$viewArea();
            long section = SectionPos.asLong(SectionPos.posToSectionCoord(position.x), 0, SectionPos.posToSectionCoord(position.z));
            Long previous = cameraSections.put(renderer, section);
            if (area != null && (previous == null || previous != section)) {
                area.repositionCamera(position.x, position.z);
            }
            ChunkRenderDispatcher dispatcher = access.wormholes$chunkDispatcher();
            if (dispatcher != null) {
                dispatcher.setCamera(position);
            }
        }
        ViewArea area = access.wormholes$viewArea();
        preparation.visible.clear();
        if (area != null && CHUNK_INFO != null) {
            discover(area, layer.camera().frustum(), layer.worldClipPlane(), position, preparation.visible);
        }
        return true;
    }

    private void discover(ViewArea area, Frustum frustum, Vector4dc plane, Vec3 camera, ObjectArrayList<Object> visible) {
        Map<ChunkRenderDispatcher.RenderChunk, Object> cache = infos.computeIfAbsent(area, ignored -> new IdentityHashMap<>());
        List<ChunkRenderDispatcher.RenderChunk> found = new ArrayList<>();
        for (ChunkRenderDispatcher.RenderChunk chunk : area.chunks) {
            AABB bounds = chunk.getBoundingBox();
            if (!frustum.isVisible(bounds) || clipped(bounds, plane) || !chunk.hasAllNeighbors()) {
                continue;
            }
            found.add(chunk);
        }
        found.sort(Comparator.comparingDouble(chunk -> chunk.getBoundingBox().getCenter().distanceToSqr(camera)));
        for (ChunkRenderDispatcher.RenderChunk chunk : found) {
            Object info = cache.get(chunk);
            if (info == null) {
                info = create(chunk);
                cache.put(chunk, info);
            }
            visible.add(info);
        }
    }

    private ObjectArrayList<Object> list(int depth) {
        while (lists.size() <= depth) {
            lists.add(new ObjectArrayList<>(4096));
        }
        return lists.get(depth);
    }

    static boolean clipped(AABB bounds, Vector4dc plane) {
        if (plane == null) {
            return false;
        }
        double x = plane.x() >= 0.0D ? bounds.maxX : bounds.minX;
        double y = plane.y() >= 0.0D ? bounds.maxY : bounds.minY;
        double z = plane.z() >= 0.0D ? bounds.maxZ : bounds.minZ;
        return plane.x() * x + plane.y() * y + plane.z() * z + plane.w() < 0.0D;
    }

    private static Object create(ChunkRenderDispatcher.RenderChunk chunk) {
        try {
            return CHUNK_INFO.invoke(chunk, (Direction) null, 0);
        } catch (Throwable failure) {
            throw new IllegalStateException("Unable to create a render chunk entry for a portal layer", failure);
        }
    }

    /** {@code LevelRenderer.RenderChunkInfo} is package-private; constructor names survive both loaders' remapping. */
    private static MethodHandle chunkInfoFactory() {
        try {
            Class<?> type = Class.forName("net.minecraft.client.renderer.LevelRenderer$RenderChunkInfo", false,
                LevelRenderer.class.getClassLoader());
            Constructor<?> constructor = type.getDeclaredConstructor(ChunkRenderDispatcher.RenderChunk.class, Direction.class, int.class);
            constructor.setAccessible(true);
            return MethodHandles.lookup().unreflectConstructor(constructor)
                .asType(MethodType.methodType(Object.class, ChunkRenderDispatcher.RenderChunk.class, Direction.class, int.class));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Portal layers draw no vanilla terrain: render chunk entries are inaccessible", failure);
            return null;
        }
    }

    private static final class Preparation {
        private final PortalLayer layer;
        private final ObjectArrayList<?> previous;
        private final double transparentX;
        private final double transparentY;
        private final double transparentZ;
        private final ObjectArrayList<Object> visible;

        private Preparation(PortalLayer layer, ObjectArrayList<?> previous, double transparentX, double transparentY, double transparentZ,
                            ObjectArrayList<Object> visible) {
            this.layer = layer;
            this.previous = previous;
            this.transparentX = transparentX;
            this.transparentY = transparentY;
            this.transparentZ = transparentZ;
            this.visible = visible;
        }
    }
}
