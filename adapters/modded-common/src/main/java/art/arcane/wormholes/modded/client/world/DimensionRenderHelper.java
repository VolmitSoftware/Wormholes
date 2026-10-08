/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: holds the 26.x light map and environment attribute probe of one client level.
 */
package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.mixin.client.ClientWorldCameraAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.phys.Vec3;

final class DimensionRenderHelper implements AutoCloseable {
    private static final long STALE_NANOS = 100_000_000L;

    private final ClientLevel level;
    private final Lightmap lightmap;
    private final EnvironmentAttributeProbe probe;
    private long tickedAt;
    private boolean ticked;
    private Vec3 renderedCamera;
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

    EnvironmentAttributeProbe probe() {
        return probe;
    }

    Lightmap lightmap() {
        return lightmap;
    }

    void prime(Vec3 position) {
        if (!ticked) {
            tick(position);
        }
    }

    void tick(Vec3 position) {
        probe.tick(level, position);
        tickedAt = System.nanoTime();
        ticked = true;
    }

    void rendered(Vec3 camera) {
        renderedCamera = camera;
    }

    void tickRendered() {
        if (renderedCamera != null) {
            tick(renderedCamera);
            renderedCamera = null;
        }
    }

    void enter(Vec3 position) {
        if (!ticked || System.nanoTime() - tickedAt > STALE_NANOS) {
            probe.reset();
        }
        tick(position);
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

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!installedInGame()) {
            lightmap.close();
        }
    }
}
