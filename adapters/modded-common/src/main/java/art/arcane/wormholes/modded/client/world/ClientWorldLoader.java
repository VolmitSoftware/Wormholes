/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: render state is kept per resident client level, built on the 26.x level extractor and
 * level renderer pair, and swapped by the seamless level switch.
 */
package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import art.arcane.wormholes.modded.client.render.sodium.SodiumSectionDiscovery;
import art.arcane.wormholes.modded.mixin.client.ClientWorldCloudAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldExtractorAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLightmapAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldMinecraftAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldOcclusionAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldSkyAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public final class ClientWorldLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SODIUM = ClientWorldLoader.class.getClassLoader()
        .getResource("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;
    private static final Map<ClientLevel, DimensionRenderHelper> RENDER_HELPER_MAP = new IdentityHashMap<>();
    private static final Map<ClientLevel, WorldRenderer> WORLD_RENDERER_MAP = new IdentityHashMap<>();
    private static ClientLevel mainLevel;
    private static ClientLevel switchedLevel;
    private static boolean reloadingOtherWorldRenderers;
    private static boolean forceFullSectionDiscovery;

    private ClientWorldLoader() {
    }

    public static void initializeIfNeeded() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (mainLevel != null || level == null) {
            return;
        }
        mainLevel = level;
        RENDER_HELPER_MAP.put(level, DimensionRenderHelper.installed(level));
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        WORLD_RENDERER_MAP.put(level, new WorldRenderer(minecraft.levelRenderer, minecraft.levelExtractor,
            minecraft.gameRenderer.gameRenderState().levelRenderState, main.width, main.height));
        FogRendererContext.initialize(level);
    }

    public static WorldRenderer worldRenderer(ClientLevel level) {
        initializeIfNeeded();
        WorldRenderer world = WORLD_RENDERER_MAP.get(level);
        if (world == null) {
            world = createWorldRenderer(level);
            WORLD_RENDERER_MAP.put(level, world);
        }
        return world;
    }

    public static LevelRenderer residentRenderer(ClientLevel level) {
        WorldRenderer world = level == mainLevel ? null : WORLD_RENDERER_MAP.get(level);
        return world == null ? null : world.renderer;
    }

    public static List<LevelRenderer> levelRenderers() {
        List<LevelRenderer> renderers = new ArrayList<>(WORLD_RENDERER_MAP.size());
        for (WorldRenderer world : WORLD_RENDERER_MAP.values()) {
            renderers.add(world.renderer);
        }
        return renderers;
    }

    public static EnvironmentAttributeProbe probe(ClientLevel level) {
        return helper(level).probe();
    }

    public static void renderedThroughPortal(ClientLevel level, Vec3 camera) {
        if (level != mainLevel) {
            helper(level).rendered(camera);
        }
    }

    public static EnvironmentAttributeProbe portalProbe(ClientLevel level, Vec3 camera) {
        DimensionRenderHelper helper = helper(level);
        if (level != mainLevel) {
            helper.prime(camera);
            helper.rendered(camera);
        }
        return helper.probe();
    }

    public static Lightmap lightmap(ClientLevel level) {
        return helper(level).lightmap();
    }

    public static void pushRenderContext(ClientLevel level) {
        FogRendererContext.pushSwapping(level);
    }

    public static void popRenderContext() {
        FogRendererContext.popSwapping();
    }

    public static void tick() {
        for (DimensionRenderHelper helper : RENDER_HELPER_MAP.values()) {
            if (helper.level() != mainLevel) {
                helper.tickRendered();
            }
        }
    }

    public static void changeLevel(ClientLevel destination, Vec3 camera) {
        initializeIfNeeded();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel source = mainLevel;
        DimensionRenderHelper helper = helper(destination);
        helper.enter(camera);
        helper.install();
        ((ClientWorldLightmapAccess) ((ClientWorldGameRendererAccess) minecraft.gameRenderer).wormholes$lightmapExtractor()).wormholes$needsUpdate(true);
        WorldRenderer previous = WORLD_RENDERER_MAP.get(source);
        WorldRenderer next = worldRenderer(destination);
        LevelRenderState mainState = minecraft.gameRenderer.gameRenderState().levelRenderState;
        LevelRenderState detached = next.state;
        next.bind(mainState);
        if (previous != null) {
            previous.bind(detached);
        }
        ClientWorldMinecraftAccess access = (ClientWorldMinecraftAccess) minecraft;
        access.wormholes$levelRenderer(next.renderer);
        access.wormholes$levelExtractor(next.extractor);
        next.attach(minecraft.gameRenderer.mainRenderTarget());
        ClientSodiumTerrain.disown(source);
        ClientSodiumTerrain.disown(destination);
        FogRendererContext.onPlayerTeleport(destination);
        mainLevel = destination;
    }

    public static void forceFullSectionDiscovery() {
        forceFullSectionDiscovery = true;
    }

    public static void beforeExtract(LevelExtractor extractor) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!forceFullSectionDiscovery || switchedLevel != null || extractor != minecraft.levelExtractor) {
            return;
        }
        forceFullSectionDiscovery = false;
        if (SODIUM) {
            SodiumSectionDiscovery.request(minecraft.levelRenderer);
            return;
        }
        LevelRenderer renderer = minecraft.levelRenderer;
        ViewArea viewArea = renderer.viewArea();
        SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
        CameraRenderState camera = minecraft.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (viewArea == null || dispatcher == null || !camera.initialized) {
            return;
        }
        viewArea.repositionCamera(SectionPos.of(camera.pos));
        dispatcher.setCameraPosition(camera.pos);
        SectionOcclusionGraph graph = renderer.sectionOcclusionGraph();
        graph.invalidate();
        graph.update(camera, minecraft.options.fov().get(), new ChunkLoadingRenderState());
        Future<?> discovery = ((ClientWorldOcclusionAccess) graph).wormholes$fullUpdateTask();
        if (discovery == null) {
            return;
        }
        try {
            discovery.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            LOGGER.error("Interrupted while discovering the sections around a crossing", failure);
        } catch (ExecutionException failure) {
            LOGGER.error("Unable to discover the sections around a crossing", failure);
        }
    }

    public static boolean switchedTo(ClientLevel level) {
        return level != null && level == switchedLevel;
    }

    public static boolean switching() {
        return switchedLevel != null;
    }

    public static void withWorldRenderer(ClientLevel level, Runnable action) {
        WorldRenderer world = level == mainLevel ? null : WORLD_RENDERER_MAP.get(level);
        if (world == null) {
            action.run();
            return;
        }
        withWorldRenderer(level, world, action);
    }

    public static void worldRendererReloaded(LevelExtractor extractor) {
        Minecraft minecraft = Minecraft.getInstance();
        if (reloadingOtherWorldRenderers || switchedLevel != null || extractor != minecraft.levelExtractor || mainLevel == null) {
            return;
        }
        reloadingOtherWorldRenderers = true;
        try {
            for (Map.Entry<ClientLevel, WorldRenderer> entry : new ArrayList<>(WORLD_RENDERER_MAP.entrySet())) {
                if (entry.getKey() != mainLevel) {
                    WorldRenderer world = entry.getValue();
                    withWorldRenderer(entry.getKey(), world, world.extractor::allChanged);
                }
            }
        } finally {
            reloadingOtherWorldRenderers = false;
        }
    }

    public static void forget(ClientLevel level) {
        if (level == mainLevel) {
            return;
        }
        WorldRenderer world = WORLD_RENDERER_MAP.remove(level);
        if (world != null) {
            dispose(level, world);
        }
        DimensionRenderHelper helper = RENDER_HELPER_MAP.remove(level);
        if (helper != null) {
            helper.close();
        }
        FogRendererContext.forget(level);
    }

    public static void cleanUp() {
        List<Map.Entry<ClientLevel, WorldRenderer>> worlds = new ArrayList<>(WORLD_RENDERER_MAP.entrySet());
        List<DimensionRenderHelper> helpers = new ArrayList<>(RENDER_HELPER_MAP.values());
        ClientLevel main = mainLevel;
        WORLD_RENDERER_MAP.clear();
        RENDER_HELPER_MAP.clear();
        mainLevel = null;
        forceFullSectionDiscovery = false;
        FogRendererContext.clear();
        RuntimeException failure = null;
        for (Map.Entry<ClientLevel, WorldRenderer> entry : worlds) {
            if (entry.getKey() == main) {
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

    public static void endFrame() {
        for (WorldRenderer world : WORLD_RENDERER_MAP.values()) {
            if (world.rendered) {
                world.rendered = false;
                world.renderer.endFrame();
            }
        }
    }

    static DimensionRenderHelper helper(ClientLevel level) {
        initializeIfNeeded();
        return RENDER_HELPER_MAP.computeIfAbsent(level, DimensionRenderHelper::create);
    }

    private static WorldRenderer createWorldRenderer(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        LevelRenderer renderer = new LevelRenderer(minecraft.getEntityRenderDispatcher(), minecraft.getBlockEntityRenderDispatcher(),
            minecraft.getModelManager(), minecraft.getTextureManager(), minecraft.getAtlasManager(), minecraft.getShaderManager(),
            minecraft.gameRenderer, main.width, main.height);
        LevelRenderState state = new LevelRenderState();
        ((ClientWorldLevelRendererAccess) renderer).wormholes$levelRenderState(state);
        LevelExtractor extractor = new LevelExtractor(minecraft, state, renderer);
        WorldRenderer world = new WorldRenderer(renderer, extractor, state, main.width, main.height);
        ((ClientWorldCloudAccess) renderer.cloudRenderer()).wormholes$texture(
            ((ClientWorldCloudAccess) minecraft.levelRenderer.cloudRenderer()).wormholes$texture());
        ClientSodiumTerrain.forget(level);
        ((PreparedLevelAccess) level).wormholes$extractor(extractor);
        withWorldRenderer(level, world, () -> extractor.setLevel(level));
        return world;
    }

    private static void withWorldRenderer(ClientLevel level, WorldRenderer world, Runnable action) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientWorldMinecraftAccess access = (ClientWorldMinecraftAccess) minecraft;
        LevelRenderer previousRenderer = minecraft.levelRenderer;
        LevelExtractor previousExtractor = minecraft.levelExtractor;
        ClientLevel previousSwitched = switchedLevel;
        access.wormholes$levelRenderer(world.renderer);
        access.wormholes$levelExtractor(world.extractor);
        switchedLevel = level;
        try {
            action.run();
        } finally {
            switchedLevel = previousSwitched;
            access.wormholes$levelRenderer(previousRenderer);
            access.wormholes$levelExtractor(previousExtractor);
        }
    }

    private static void dispose(ClientLevel level, WorldRenderer world) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            withWorldRenderer(level, world, () -> world.extractor.setLevel(null));
        } finally {
            world.renderer.close();
            if (((PreparedLevelAccess) level).wormholes$extractor() == world.extractor) {
                ((PreparedLevelAccess) level).wormholes$extractor(new PreparedLevelExtractor(minecraft));
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

    public static final class WorldRenderer {
        private final LevelRenderer renderer;
        private final LevelExtractor extractor;
        private LevelRenderState state;
        private int width;
        private int height;
        private boolean rendered;

        private WorldRenderer(LevelRenderer renderer, LevelExtractor extractor, LevelRenderState state, int width, int height) {
            this.renderer = renderer;
            this.extractor = extractor;
            this.state = state;
            this.width = width;
            this.height = height;
        }

        public LevelRenderer renderer() {
            return renderer;
        }

        public LevelExtractor extractor() {
            return extractor;
        }

        public LevelRenderState state() {
            return state;
        }

        public void rendered() {
            rendered = true;
        }

        public void attach(RenderTarget target) {
            if (width != target.width || height != target.height) {
                renderer.resize(target.width, target.height);
                width = target.width;
                height = target.height;
            }
            SkyRenderer sky = renderer.skyRenderer();
            if (sky != null && ((ClientWorldSkyAccess) sky).wormholes$renderTarget() != target) {
                ((ClientWorldSkyAccess) sky).wormholes$renderTarget(target);
            }
        }

        private void bind(LevelRenderState next) {
            ((ClientWorldLevelRendererAccess) renderer).wormholes$levelRenderState(next);
            ((ClientWorldExtractorAccess) extractor).wormholes$levelRenderState(next);
            state = next;
        }
    }
}
