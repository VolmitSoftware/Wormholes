/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: render state is kept per resident client level on the 1.20.1 level renderer, light texture
 * and fog statics, and swapped by the seamless level switch.
 */
package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.render.WormholesRenderers;
import art.arcane.wormholes.modded.client.render.sodium.SodiumSectionDiscovery;
import art.arcane.wormholes.modded.mixin.client.ClientWorldEntityDispatcherAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldMinecraftAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Owns one {@link LevelRenderer}, {@link RenderBuffers} and {@link LightTexture} per client level and swaps the
 * render context (renderer, light texture, fog statics) between them.
 *
 * <p>1.20.1 never frees the native compile buffers of a {@link ChunkRenderDispatcher}, so a released resident renderer
 * is not disposed: its GPU sections, post chains and level references are dropped and the renderer, its dispatcher and
 * its buffers wait in a spare pool for the next resident level. The pool never grows past the peak number of resident
 * renderers that were alive at once.
 */
public final class ClientWorldLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SODIUM = WormholesRenderers.installed(WormholesRenderers.SODIUM);
    private static final boolean RENDERER_PER_LEVEL = WormholesRenderers.worldRendererPerLevel();
    private static final Map<ClientLevel, DimensionRenderHelper> RENDER_HELPER_MAP = new IdentityHashMap<>();
    private static final Map<ClientLevel, LevelWorld> WORLD_RENDERER_MAP = new IdentityHashMap<>();
    private static final ArrayDeque<LevelWorld> SPARE = new ArrayDeque<>();
    private static final ArrayDeque<RenderContext> CONTEXTS = new ArrayDeque<>();
    private static ClientLevel mainLevel;
    private static ClientLevel switchedLevel;
    private static boolean reloadingOtherWorldRenderers;
    private static boolean forceFullSectionDiscovery;

    static {
        ClientArrivalPreparation.staging(new PreparedStaging());
    }

    private ClientWorldLoader() {
    }

    /** Records the current {@code minecraft.level} as the main level on first use. */
    public static void initializeIfNeeded() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (mainLevel != null || level == null) {
            return;
        }
        mainLevel = level;
        RENDER_HELPER_MAP.put(level, DimensionRenderHelper.installed(level));
        RenderTarget main = ClientRenderAccess.mainTarget(minecraft);
        WORLD_RENDERER_MAP.put(level, new LevelWorld(minecraft.levelRenderer, minecraft.renderBuffers(), main.width, main.height));
        FogRendererContext.initialize(level);
    }

    /** The level the player is in, as opposed to resident levels seen through portals. */
    public static ClientLevel mainLevel() {
        return mainLevel;
    }

    /** The renderer set for {@code level}, created on first use when renderers are per level. */
    public static WorldRenderer worldRenderer(ClientLevel level) {
        initializeIfNeeded();
        LevelWorld world = WORLD_RENDERER_MAP.get(level);
        if (world == null) {
            if (!RENDERER_PER_LEVEL) {
                throw new IllegalStateException("The terrain renderer keeps one terrain store, so only the main level has a world renderer");
            }
            world = createWorldRenderer(level);
            WORLD_RENDERER_MAP.put(level, world);
        }
        return world;
    }

    /** Runs {@code action} with {@code level}'s renderer bound as {@code minecraft.levelRenderer}. */
    public static void withWorldRenderer(ClientLevel level, Runnable action) {
        LevelWorld world = level == mainLevel && switchedLevel == null ? null : WORLD_RENDERER_MAP.get(level);
        if (world == null) {
            action.run();
            return;
        }
        world.attach(ClientRenderAccess.mainTarget(Minecraft.getInstance()));
        withWorldRenderer(level, world, action);
    }

    /** The renderer of a resident (non-main) level, or {@code null} when it has none. */
    public static LevelRenderer residentRenderer(ClientLevel level) {
        LevelWorld world = level == mainLevel ? null : WORLD_RENDERER_MAP.get(level);
        return world == null ? null : world.renderer;
    }

    /** Every live level renderer, main first. */
    public static List<LevelRenderer> levelRenderers() {
        List<LevelRenderer> renderers = new ArrayList<>(WORLD_RENDERER_MAP.size());
        LevelWorld main = mainLevel == null ? null : WORLD_RENDERER_MAP.get(mainLevel);
        if (main != null) {
            renderers.add(main.renderer);
        }
        for (LevelWorld world : WORLD_RENDERER_MAP.values()) {
            if (world != main) {
                renderers.add(world.renderer);
            }
        }
        return renderers;
    }

    /** The light texture owned by {@code level}, rebuilt from that level when it is stale. */
    public static LightTexture lightTexture(ClientLevel level) {
        DimensionRenderHelper helper = helper(level);
        helper.update();
        return helper.lightTexture();
    }

    /** Notes that {@code level} was drawn through a portal from {@code camera} this frame. */
    public static void renderedThroughPortal(ClientLevel level, Vec3 camera) {
        LevelWorld world = level == mainLevel ? null : WORLD_RENDERER_MAP.get(level);
        if (world != null) {
            world.rendered();
        }
    }

    /**
     * Swaps fog statics, the shader fog state and the game renderer's light texture to {@code level}; pair with
     * {@link #popRenderContext()}.
     */
    public static void pushRenderContext(ClientLevel level) {
        initializeIfNeeded();
        Minecraft minecraft = Minecraft.getInstance();
        DimensionRenderHelper helper = helper(level);
        float[] color = RenderSystem.getShaderFogColor();
        CONTEXTS.push(new RenderContext(minecraft.gameRenderer.lightTexture(), RenderSystem.getShaderFogStart(),
            RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogShape(), Arrays.copyOf(color, color.length)));
        FogRendererContext.pushSwapping(level);
        helper.update();
        ((ClientWorldGameRendererAccess) minecraft.gameRenderer).wormholes$lightTexture(helper.lightTexture());
    }

    /** Restores the context saved by the last {@link #pushRenderContext(ClientLevel)}. */
    public static void popRenderContext() {
        RenderContext context = CONTEXTS.pop();
        FogRendererContext.popSwapping();
        ((ClientWorldGameRendererAccess) Minecraft.getInstance().gameRenderer).wormholes$lightTexture(context.lightTexture());
        RenderSystem.setShaderFogStart(context.fogStart());
        RenderSystem.setShaderFogEnd(context.fogEnd());
        RenderSystem.setShaderFogShape(context.fogShape());
        float[] color = context.fogColor();
        RenderSystem.setShaderFogColor(color[0], color[1], color[2], color[3]);
    }

    /** Ticks resident renderers and light textures. */
    public static void tick() {
        for (DimensionRenderHelper helper : RENDER_HELPER_MAP.values()) {
            if (helper.level() != mainLevel) {
                helper.tick();
            }
        }
        for (Map.Entry<ClientLevel, LevelWorld> entry : WORLD_RENDERER_MAP.entrySet()) {
            if (entry.getKey() != mainLevel) {
                entry.getValue().renderer.tick();
            }
        }
    }

    /** Whether each level gets its own {@link LevelRenderer}; false when a terrain mod keeps one shared store. */
    public static boolean rendererPerLevel() {
        return RENDERER_PER_LEVEL;
    }

    /**
     * Makes {@code destination} the main level's render context without a loading screen: its renderer becomes
     * {@code minecraft.levelRenderer}, its light texture the game renderer's, and the entity and block entity
     * dispatchers move to it. The caller swaps {@code minecraft.level}, the connection and the player.
     */
    public static void changeLevel(ClientLevel destination, Vec3 camera) {
        initializeIfNeeded();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel source = mainLevel;
        if (destination == source) {
            return;
        }
        helper(destination).install();
        if (!RENDERER_PER_LEVEL) {
            LevelWorld main = WORLD_RENDERER_MAP.remove(source);
            ((PreparedLevelAccess) source).wormholes$extractor(PreparedLevelExtractor.shared());
            ((PreparedLevelAccess) destination).wormholes$extractor(main.renderer);
            main.renderer.setLevel(destination);
            minecraft.getBlockEntityRenderDispatcher().setLevel(destination);
            WORLD_RENDERER_MAP.put(destination, main);
            FogRendererContext.onPlayerTeleport(destination);
            mainLevel = destination;
            return;
        }
        LevelWorld next = (LevelWorld) worldRenderer(destination);
        ((ClientWorldMinecraftAccess) minecraft).wormholes$levelRenderer(next.renderer);
        next.attach(ClientRenderAccess.mainTarget(minecraft));
        minecraft.getEntityRenderDispatcher().setLevel(destination);
        minecraft.getBlockEntityRenderDispatcher().setLevel(destination);
        next.renderer.needsUpdate();
        FogRendererContext.onPlayerTeleport(destination);
        mainLevel = destination;
    }

    /** Compiled sections survive until the renderer's level changes on 1.20.1, so there is nothing to pin. */
    public static void retainArrivalGeometry(ClientLevel level) {
    }

    /** Hands the prepared level's renderer to {@code destination} on arrival. */
    public static void adoptArrival(ClientLevel prepared, ClientLevel destination, Vec3 camera) {
        initializeIfNeeded();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel source = mainLevel;
        LevelWorld next = WORLD_RENDERER_MAP.get(prepared);
        LevelWorld previous = WORLD_RENDERER_MAP.get(source);
        if (next == null || previous == null || next == previous) {
            throw new IllegalStateException("Prepared arrival renderer is missing or already installed");
        }
        DimensionRenderHelper destinationHelper = helper(destination);
        DimensionRenderHelper sourceHelper = helper(source);
        next.attach(ClientRenderAccess.mainTarget(minecraft));
        LevelRenderer oldRenderer = ((PreparedLevelAccess) destination).wormholes$extractor();
        ClientWorldMinecraftAccess access = (ClientWorldMinecraftAccess) minecraft;
        try {
            next.bindLevel(destination);
            ((PreparedLevelAccess) destination).wormholes$extractor(next.renderer);
            access.wormholes$levelRenderer(next.renderer);
            destinationHelper.install();
            minecraft.getEntityRenderDispatcher().setLevel(destination);
            minecraft.getBlockEntityRenderDispatcher().setLevel(destination);
            FogRendererContext.onPlayerTeleport(destination);
        } catch (RuntimeException failure) {
            access.wormholes$levelRenderer(previous.renderer);
            next.bindLevel(prepared);
            ((PreparedLevelAccess) destination).wormholes$extractor(oldRenderer);
            sourceHelper.install();
            minecraft.getEntityRenderDispatcher().setLevel(source);
            minecraft.getBlockEntityRenderDispatcher().setLevel(source);
            FogRendererContext.onPlayerTeleport(source);
            throw failure;
        }
        WORLD_RENDERER_MAP.remove(source);
        WORLD_RENDERER_MAP.remove(prepared);
        WORLD_RENDERER_MAP.put(destination, next);
        mainLevel = destination;
        forceFullSectionDiscovery();
        for (ClientLevel level : new ArrayList<>(WORLD_RENDERER_MAP.keySet())) {
            if (level != destination) {
                releaseArrivalSource(level);
            }
        }
        for (ClientLevel level : List.of(source, prepared)) {
            if (level != destination) {
                releaseArrivalHelper(level);
            }
        }
        try {
            dispose(source, previous);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to release the previous arrival renderer", failure);
        }
    }

    /** Binds the main renderer to {@code level}. */
    public static void bindMainRenderer(ClientLevel level) {
        ((PreparedLevelAccess) level).wormholes$extractor(Minecraft.getInstance().levelRenderer);
    }

    /** Recompiles the main renderer's terrain. */
    public static void rebuildMainTerrain() {
        Minecraft.getInstance().levelRenderer.allChanged();
    }

    /** Marks a section dirty in the bound renderer. */
    public static void sectionDirty(int x, int y, int z) {
        Minecraft.getInstance().levelRenderer.setSectionDirty(x, y, z);
    }

    /** Requests a full visible-section search on the main renderer's next frame, run synchronously. */
    public static void forceFullSectionDiscovery() {
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        if (SODIUM) {
            SodiumSectionDiscovery.request(renderer);
            return;
        }
        forceFullSectionDiscovery = true;
        renderer.needsUpdate();
    }

    /**
     * Schedules the vanilla full section update of {@code renderer}, or runs it in place when a forced discovery is
     * pending for the main renderer, so the first frame after a crossing sees every section.
     */
    public static Future<?> discoverSections(LevelRenderer renderer, ExecutorService executor, Runnable discovery) {
        if (!forceFullSectionDiscovery || switchedLevel != null || renderer != Minecraft.getInstance().levelRenderer) {
            return executor.submit(discovery);
        }
        forceFullSectionDiscovery = false;
        try {
            discovery.run();
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to discover the sections around a crossing", failure);
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Compiles terrain of {@code level} around {@code camera} ahead of an arrival; Sodium only, vanilla compiles on draw. */
    public static void prepareArrivalTerrain(ClientLevel level, Vec3 camera, boolean fullColumns) {
        if (!SODIUM) {
            return;
        }
        initializeIfNeeded();
        if (level == mainLevel || switchedLevel != null && switchedLevel != level) {
            return;
        }
        LevelWorld world = (LevelWorld) worldRenderer(level);
        withWorldRenderer(level, world, () -> SodiumSectionDiscovery.prepareArrival(world.renderer, camera, fullColumns));
        world.rendered();
    }

    /** Whether the bound renderer currently belongs to {@code level} through {@link #withWorldRenderer}. */
    public static boolean switchedTo(ClientLevel level) {
        return level != null && level == switchedLevel;
    }

    /** Whether a resident renderer is bound. */
    public static boolean switching() {
        return switchedLevel != null;
    }

    /** Propagates a main-renderer {@code allChanged} to every resident renderer. */
    public static void worldRendererReloaded(LevelRenderer renderer) {
        Minecraft minecraft = Minecraft.getInstance();
        if (reloadingOtherWorldRenderers || switchedLevel != null || renderer != minecraft.levelRenderer || mainLevel == null) {
            return;
        }
        reloadingOtherWorldRenderers = true;
        try {
            for (Map.Entry<ClientLevel, LevelWorld> entry : new ArrayList<>(WORLD_RENDERER_MAP.entrySet())) {
                if (entry.getKey() != mainLevel) {
                    LevelWorld world = entry.getValue();
                    withWorldRenderer(entry.getKey(), world, world.renderer::allChanged);
                }
            }
        } finally {
            reloadingOtherWorldRenderers = false;
        }
    }

    /**
     * Propagates a resource reload of the registered renderer to every other live renderer, so their post chains use
     * the reloaded shaders; a spare renderer drops the chains it just built.
     */
    public static void resourcesReloaded(LevelRenderer renderer) {
        if (reloadingOtherWorldRenderers) {
            return;
        }
        for (LevelWorld spare : SPARE) {
            if (spare.renderer == renderer) {
                spare.dropPostChains();
                return;
            }
        }
        reloadingOtherWorldRenderers = true;
        try {
            for (LevelWorld world : WORLD_RENDERER_MAP.values()) {
                if (world.renderer != renderer) {
                    world.renderer.onResourceManagerReload(Minecraft.getInstance().getResourceManager());
                }
            }
        } finally {
            reloadingOtherWorldRenderers = false;
        }
    }

    /** Frees the renderer and context of {@code level}. */
    public static void forget(ClientLevel level) {
        if (level == mainLevel) {
            return;
        }
        LevelWorld world = WORLD_RENDERER_MAP.remove(level);
        if (world != null) {
            dispose(level, world);
        }
        DimensionRenderHelper helper = RENDER_HELPER_MAP.remove(level);
        if (helper != null) {
            helper.close();
        }
        FogRendererContext.forget(level);
    }

    /** Frees every resident renderer, on disconnect or a vanilla level change. */
    public static void cleanUp() {
        List<Map.Entry<ClientLevel, LevelWorld>> worlds = new ArrayList<>(WORLD_RENDERER_MAP.entrySet());
        List<DimensionRenderHelper> helpers = new ArrayList<>(RENDER_HELPER_MAP.values());
        ClientLevel main = mainLevel;
        LevelRenderer installed = Minecraft.getInstance().levelRenderer;
        WORLD_RENDERER_MAP.clear();
        RENDER_HELPER_MAP.clear();
        CONTEXTS.clear();
        mainLevel = null;
        forceFullSectionDiscovery = false;
        FogRendererContext.clear();
        RuntimeException failure = null;
        for (Map.Entry<ClientLevel, LevelWorld> entry : worlds) {
            if (entry.getKey() == main || entry.getValue().renderer == installed) {
                continue;
            }
            try {
                dispose(entry.getKey(), entry.getValue());
            } catch (RuntimeException cleanup) {
                failure = record(failure, cleanup);
            }
        }
        for (DimensionRenderHelper helper : helpers) {
            try {
                helper.close();
            } catch (RuntimeException cleanup) {
                failure = record(failure, cleanup);
            }
        }
        if (failure != null) {
            LOGGER.error("Unable to release the render state of resident levels", failure);
        }
    }

    /** Called once at the end of every frame. */
    public static void endFrame() {
        for (LevelWorld world : WORLD_RENDERER_MAP.values()) {
            world.rendered = false;
        }
    }

    static DimensionRenderHelper helper(ClientLevel level) {
        initializeIfNeeded();
        return RENDER_HELPER_MAP.computeIfAbsent(level, DimensionRenderHelper::create);
    }

    private static void releaseArrivalHelper(ClientLevel level) {
        try {
            DimensionRenderHelper old = RENDER_HELPER_MAP.remove(level);
            if (old != null) {
                old.close();
            }
            FogRendererContext.forget(level);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to release the previous arrival lighting", failure);
        }
    }

    private static void releaseArrivalSource(ClientLevel level) {
        try {
            forget(level);
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to release a resident level after arrival", failure);
        }
    }

    private static LevelWorld createWorldRenderer(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = ClientRenderAccess.mainTarget(minecraft);
        LevelWorld world = SPARE.poll();
        if (world == null) {
            RenderBuffers buffers = new RenderBuffers();
            world = new LevelWorld(new LevelRenderer(minecraft, minecraft.getEntityRenderDispatcher(),
                minecraft.getBlockEntityRenderDispatcher(), buffers), buffers, main.width, main.height);
        } else {
            world.reattachDispatcher();
        }
        boolean outer = reloadingOtherWorldRenderers;
        reloadingOtherWorldRenderers = true;
        try {
            world.renderer.onResourceManagerReload(minecraft.getResourceManager());
        } finally {
            reloadingOtherWorldRenderers = outer;
        }
        world.sized(main.width, main.height);
        ((PreparedLevelAccess) level).wormholes$extractor(world.renderer);
        LevelWorld created = world;
        withWorldRenderer(level, world, () -> created.renderer.setLevel(level));
        return world;
    }

    private static void withWorldRenderer(ClientLevel level, LevelWorld world, Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientWorldMinecraftAccess access = (ClientWorldMinecraftAccess) minecraft;
        EntityRenderDispatcher entities = minecraft.getEntityRenderDispatcher();
        BlockEntityRenderDispatcher blockEntities = minecraft.getBlockEntityRenderDispatcher();
        LevelRenderer previousRenderer = minecraft.levelRenderer;
        ClientLevel previousSwitched = switchedLevel;
        Level entityLevel = ((ClientWorldEntityDispatcherAccess) entities).wormholes$level();
        Camera entityCamera = entities.camera;
        Entity crosshair = entities.crosshairPickEntity;
        Level blockEntityLevel = blockEntities.level;
        Camera blockEntityCamera = blockEntities.camera;
        HitResult blockEntityHit = blockEntities.cameraHitResult;
        access.wormholes$levelRenderer(world.renderer);
        switchedLevel = level;
        try {
            action.run();
        } finally {
            switchedLevel = previousSwitched;
            access.wormholes$levelRenderer(previousRenderer);
            entities.setLevel(entityLevel);
            entities.camera = entityCamera;
            entities.crosshairPickEntity = crosshair;
            blockEntities.level = blockEntityLevel;
            blockEntities.camera = blockEntityCamera;
            blockEntities.cameraHitResult = blockEntityHit;
        }
    }

    private static void dispose(ClientLevel level, LevelWorld world) {
        boolean released = false;
        try {
            ClientLevel keep = mainLevel != null && mainLevel != level ? mainLevel : Minecraft.getInstance().level;
            withWorldRenderer(level, world, () -> world.release(keep == level ? null : keep));
            released = true;
        } finally {
            if (((PreparedLevelAccess) level).wormholes$extractor() == world.renderer) {
                ((PreparedLevelAccess) level).wormholes$extractor(PreparedLevelExtractor.shared());
            }
            if (released) {
                SPARE.push(world);
            }
        }
    }

    private static RuntimeException record(RuntimeException failure, RuntimeException cleanup) {
        if (failure == null) {
            return cleanup;
        }
        failure.addSuppressed(cleanup);
        return failure;
    }

    /** The renderer objects that belong to one level. */
    public interface WorldRenderer {
        /** The level renderer. */
        LevelRenderer renderer();

        /** The buffer sources the renderer draws through. */
        RenderBuffers buffers();

        /** Marks the renderer as drawn this frame. */
        void rendered();
    }

    private record RenderContext(LightTexture lightTexture, float fogStart, float fogEnd, FogShape fogShape, float[] fogColor) {
    }

    private static final class LevelWorld implements WorldRenderer {
        private final LevelRenderer renderer;
        private final RenderBuffers buffers;
        private ChunkRenderDispatcher parkedDispatcher;
        private int width;
        private int height;
        private boolean rendered;

        private LevelWorld(LevelRenderer renderer, RenderBuffers buffers, int width, int height) {
            this.renderer = renderer;
            this.buffers = buffers;
            this.width = width;
            this.height = height;
        }

        @Override
        public LevelRenderer renderer() {
            return renderer;
        }

        @Override
        public RenderBuffers buffers() {
            return buffers;
        }

        @Override
        public void rendered() {
            rendered = true;
        }

        private void attach(RenderTarget target) {
            if (width != target.width || height != target.height) {
                renderer.resize(target.width, target.height);
                sized(target.width, target.height);
            }
        }

        private void sized(int width, int height) {
            this.width = width;
            this.height = height;
        }

        private void bindLevel(ClientLevel level) {
            ((ClientWorldLevelRendererAccess) renderer).wormholes$level(level);
            ChunkRenderDispatcher dispatcher = renderer.getChunkRenderDispatcher();
            if (dispatcher != null) {
                dispatcher.setLevel(level);
            }
        }

        /**
         * Unbinds the level while keeping the chunk dispatcher: {@code setLevel(null)} would dispose it and leak its
         * native compile buffers, so it is detached first, its queue cancelled and its level moved to {@code keep}.
         */
        private void release(ClientLevel keep) {
            ClientWorldLevelRendererAccess access = (ClientWorldLevelRendererAccess) renderer;
            ChunkRenderDispatcher dispatcher = renderer.getChunkRenderDispatcher();
            if (dispatcher != null) {
                dispatcher.blockUntilClear();
                access.wormholes$dispatcher(null);
            }
            parkedDispatcher = dispatcher;
            renderer.setLevel(null);
            access.wormholes$recentlyCompiled().clear();
            if (dispatcher != null && keep != null) {
                dispatcher.setLevel(keep);
            }
            dropPostChains();
            rendered = false;
        }

        private void reattachDispatcher() {
            if (parkedDispatcher != null) {
                ((ClientWorldLevelRendererAccess) renderer).wormholes$dispatcher(parkedDispatcher);
                parkedDispatcher = null;
            }
        }

        private void dropPostChains() {
            ClientWorldLevelRendererAccess access = (ClientWorldLevelRendererAccess) renderer;
            access.wormholes$deinitTransparency();
            PostChain outline = access.wormholes$entityEffect();
            if (outline != null) {
                outline.close();
                access.wormholes$entityEffect(null);
                access.wormholes$entityTarget(null);
            }
        }
    }
}
