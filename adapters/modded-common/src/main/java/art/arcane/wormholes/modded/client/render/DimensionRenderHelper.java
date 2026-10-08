/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: holds the 26.x light map, environment attribute probe and fog buffer of one client level.
 */
package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.ClientWorldCameraAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.phys.Vec3;

final class DimensionRenderHelper implements AutoCloseable {
    private final ClientLevel level;
    private final Lightmap lightmap;
    private final EnvironmentAttributeProbe probe;
    private final LightmapRenderState lightmapState = new LightmapRenderState();
    private FogRenderer fogRenderer;
    private long tickedAt = Long.MIN_VALUE;
    private boolean closed;

    private DimensionRenderHelper(ClientLevel level, Lightmap lightmap, EnvironmentAttributeProbe probe) {
        this.level = level;
        this.lightmap = lightmap;
        this.probe = probe;
    }

    static DimensionRenderHelper installed(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        return new DimensionRenderHelper(level, ((ClientWorldGameRendererAccess) minecraft.gameRenderer).wormholes$lightmap(),
            minecraft.gameRenderer.mainCamera().attributeProbe());
    }

    static DimensionRenderHelper create(ClientLevel level) {
        return new DimensionRenderHelper(level, new Lightmap(), new EnvironmentAttributeProbe());
    }

    ClientLevel level() {
        return level;
    }

    Lightmap lightmap() {
        return lightmap;
    }

    LightmapRenderState lightmapState() {
        return lightmapState;
    }

    EnvironmentAttributeProbe probe() {
        return probe;
    }

    FogRenderer fogRenderer() {
        if (fogRenderer == null) {
            fogRenderer = new FogRenderer();
        }
        return fogRenderer;
    }

    void tick(Vec3 position, long tick) {
        probe.tick(level, position);
        tickedAt = tick;
    }

    void enter(Vec3 position, long tick) {
        if (tickedAt < tick - 1L) {
            probe.reset();
        }
        tick(position, tick);
    }

    boolean installedInGame() {
        Minecraft minecraft = Minecraft.getInstance();
        return ((ClientWorldGameRendererAccess) minecraft.gameRenderer).wormholes$lightmap() == lightmap;
    }

    void install() {
        Minecraft minecraft = Minecraft.getInstance();
        ((ClientWorldGameRendererAccess) minecraft.gameRenderer).wormholes$lightmap(lightmap);
        ((ClientWorldCameraAccess) minecraft.gameRenderer.mainCamera()).wormholes$attributeProbe(probe);
    }

    void endFrame() {
        if (fogRenderer != null) {
            fogRenderer.endFrame();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!installedInGame()) {
                lightmap.close();
            }
        } finally {
            if (fogRenderer != null) {
                fogRenderer.close();
                fogRenderer = null;
            }
        }
    }
}
