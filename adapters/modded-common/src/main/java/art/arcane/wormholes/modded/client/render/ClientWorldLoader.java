/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: render state is kept per resident client level and swapped by the seamless level switch.
 */
package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLightmapAccess;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ClientWorldLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ClientLevel, DimensionRenderHelper> RENDER_HELPER_MAP = new IdentityHashMap<>();
    private static ClientLevel mainLevel;
    private static long ticks;

    private ClientWorldLoader() {
    }

    public static void initializeIfNeeded() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || mainLevel == level && RENDER_HELPER_MAP.containsKey(level)) {
            return;
        }
        if (mainLevel != null) {
            cleanUp();
        }
        mainLevel = level;
        RENDER_HELPER_MAP.put(level, DimensionRenderHelper.installed(level));
        FogRendererContext.initialize(level);
    }

    public static void tick(List<PortalWorldView> views, Vec3 camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || camera == null) {
            return;
        }
        initializeIfNeeded();
        ticks++;
        Vec3d eye = new Vec3d(camera.x, camera.y, camera.z);
        for (DimensionRenderHelper helper : RENDER_HELPER_MAP.values()) {
            if (helper.level() == mainLevel) {
                continue;
            }
            PortalWorldView view = nearest(views, helper.level(), eye);
            if (view != null) {
                Vec3d mapped = view.levelPoint(eye);
                helper.tick(new Vec3(mapped.x(), mapped.y(), mapped.z()), ticks);
            }
        }
    }

    public static void changeLevel(ClientLevel destination, Vec3 camera) {
        initializeIfNeeded();
        Minecraft minecraft = Minecraft.getInstance();
        DimensionRenderHelper helper = helper(destination);
        helper.enter(camera, ticks);
        helper.install();
        ((ClientWorldLightmapAccess) ((ClientWorldGameRendererAccess) minecraft.gameRenderer).wormholes$lightmapExtractor()).wormholes$needsUpdate(true);
        FogRendererContext.onPlayerTeleport(destination);
        mainLevel = destination;
    }

    public static void forget(ClientLevel level) {
        if (level == mainLevel) {
            return;
        }
        DimensionRenderHelper helper = RENDER_HELPER_MAP.remove(level);
        if (helper != null) {
            helper.close();
        }
        FogRendererContext.forget(level);
    }

    public static void cleanUp() {
        List<DimensionRenderHelper> helpers = new ArrayList<>(RENDER_HELPER_MAP.values());
        RENDER_HELPER_MAP.clear();
        mainLevel = null;
        FogRendererContext.clear();
        RuntimeException failure = null;
        for (DimensionRenderHelper helper : helpers) {
            try {
                helper.close();
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            LOGGER.error("Unable to release the render state of resident levels", failure);
        }
    }

    static DimensionRenderHelper helper(ClientLevel level) {
        initializeIfNeeded();
        return RENDER_HELPER_MAP.computeIfAbsent(level, DimensionRenderHelper::create);
    }

    static ClientLevel mainLevel() {
        return mainLevel;
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
}
