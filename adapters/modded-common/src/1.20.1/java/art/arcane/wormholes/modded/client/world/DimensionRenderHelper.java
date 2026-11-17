/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: holds the 1.20.1 light texture of one client level.
 */
package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.mixin.client.ClientWorldGameRendererAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLightmapAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;

final class DimensionRenderHelper implements AutoCloseable {
    private final ClientLevel level;
    private final LightTexture lightTexture;
    private boolean closed;

    private DimensionRenderHelper(ClientLevel level, LightTexture lightTexture) {
        this.level = level;
        this.lightTexture = lightTexture;
    }

    static DimensionRenderHelper installed(ClientLevel level) {
        return new DimensionRenderHelper(level, Minecraft.getInstance().gameRenderer.lightTexture());
    }

    static DimensionRenderHelper create(ClientLevel level) {
        Minecraft minecraft = Minecraft.getInstance();
        DimensionRenderHelper helper = new DimensionRenderHelper(level, new LightTexture(minecraft.gameRenderer, minecraft));
        helper.invalidate();
        return helper;
    }

    ClientLevel level() {
        return level;
    }

    LightTexture lightTexture() {
        return lightTexture;
    }

    void tick() {
        lightTexture.tick();
    }

    void invalidate() {
        ((ClientWorldLightmapAccess) lightTexture).wormholes$needsUpdate(true);
    }

    /** Rebuilds the light texture from this level when it is stale; the texture reads {@code minecraft.level}. */
    void update() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel previous = minecraft.level;
        minecraft.level = level;
        try {
            lightTexture.updateLightTexture(minecraft.getFrameTime());
        } finally {
            minecraft.level = previous;
        }
    }

    boolean installedInGame() {
        return Minecraft.getInstance().gameRenderer.lightTexture() == lightTexture;
    }

    void install() {
        ((ClientWorldGameRendererAccess) Minecraft.getInstance().gameRenderer).wormholes$lightTexture(lightTexture);
        invalidate();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!installedInGame()) {
            Minecraft.getInstance().getTextureManager().release(((ClientWorldLightmapAccess) lightTexture).wormholes$location());
        }
    }
}
