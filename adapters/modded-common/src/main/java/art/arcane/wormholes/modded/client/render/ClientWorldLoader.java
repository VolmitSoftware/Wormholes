/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: render state is kept per resident client level, built on the 26.x level extractor and
 * level renderer pair, and swapped by the seamless level switch.
 */
package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.mixin.client.ClientWorldCloudAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldExtractorAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLightmapAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldMinecraftAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldSkyAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedLevelAccess;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ClientWorldLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ClientLevel, DimensionRenderHelper> RENDER_HELPER_MAP = new IdentityHashMap<>();
    private static final Map<ClientLevel, WorldRenderer> WORLD_RENDERER_MAP = new IdentityHashMap<>();
    private static ClientLevel mainLevel;
    private static ClientLevel switchedLevel;
    private static boolean reloadingOtherWorldRenderers;

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

    public static void tick(List<PortalWorldView> views, Vec3 camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || camera == null) {
            return;
        }
        initializeIfNeeded();
        Vec3d eye = new Vec3d(camera.x, camera.y, camera.z);
        for (DimensionRenderHelper helper : RENDER_HELPER_MAP.values()) {
            if (helper.level() == mainLevel) {
                continue;
            }
            PortalWorldView view = nearest(views, helper.level(), eye);
            if (view != null) {
                Vec3d mapped = view.levelPoint(eye);
                helper.tick(new Vec3(mapped.x(), mapped.y(), mapped.z()));
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

    public static boolean hasWorldRenderer(ClientLevel level) {
        return WORLD_RENDERER_MAP.containsKey(level);
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
        if (reloadingOtherWorldRenderers || switchedLevel != null || extractor != minecraft.levelExtractor
            || mainLevel == null) {
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
        for (DimensionRenderHelper helper : RENDER_HELPER_MAP.values()) {
            helper.endFrame();
        }
    }

    static DimensionRenderHelper helper(ClientLevel level) {
        initializeIfNeeded();
        return RENDER_HELPER_MAP.computeIfAbsent(level, DimensionRenderHelper::create);
    }

    static WorldRenderer worldRenderer(ClientLevel level) {
        initializeIfNeeded();
        WorldRenderer world = WORLD_RENDERER_MAP.get(level);
        if (world == null) {
            world = createWorldRenderer(level);
            WORLD_RENDERER_MAP.put(level, world);
        }
        return world;
    }

    static ClientLevel mainLevel() {
        return mainLevel;
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
        LOGGER.info("Created the level renderer for resident level {}", level.dimension().identifier());
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

    private static PortalWorldView nearest(List<PortalWorldView> views, ClientLevel level, Vec3d eye) {
        PortalWorldView nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < views.size(); index++) {
            PortalWorldView view = views.get(index);
            if (view.level() != level) {
                continue;
            }
            double candidate = view.surface().point(view.geometry().apertureArea().center()).subtract(eye).lengthSquared();
            if (candidate < distance) {
                distance = candidate;
                nearest = view;
            }
        }
        return nearest;
    }

    static final class WorldRenderer {
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

        LevelRenderer renderer() {
            return renderer;
        }

        LevelExtractor extractor() {
            return extractor;
        }

        LevelRenderState state() {
            return state;
        }

        void rendered() {
            rendered = true;
        }

        void attach(RenderTarget target) {
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
