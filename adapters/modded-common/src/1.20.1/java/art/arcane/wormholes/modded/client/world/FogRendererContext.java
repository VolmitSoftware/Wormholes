/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: carries the 1.20.1 FogRenderer colour and biome transition statics per client level.
 */
package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.mixin.client.ClientWorldFogAccess;
import net.minecraft.client.multiplayer.ClientLevel;

final class FogRendererContext {
    private static final StaticFieldsSwappingManager<FogRendererContext> SWAPPING =
        new StaticFieldsSwappingManager<>(FogRendererContext::copyToStaticFields, FogRendererContext::copyFromStaticFields, FogRendererContext::new);

    private float red;
    private float green;
    private float blue;
    private int targetBiomeFog = -1;
    private int previousBiomeFog = -1;
    private long biomeChangedTime = -1L;

    static void initialize(ClientLevel level) {
        SWAPPING.setOuterLevel(level);
    }

    static void pushSwapping(ClientLevel level) {
        SWAPPING.pushSwapping(level);
    }

    static void popSwapping() {
        SWAPPING.popSwapping();
    }

    static void onPlayerTeleport(ClientLevel to) {
        SWAPPING.updateOuterLevelAndChangeContext(to);
    }

    static void forget(ClientLevel level) {
        SWAPPING.forget(level);
    }

    static void clear() {
        SWAPPING.clear();
    }

    private static void copyToStaticFields(FogRendererContext context) {
        ClientWorldFogAccess.wormholes$red(context.red);
        ClientWorldFogAccess.wormholes$green(context.green);
        ClientWorldFogAccess.wormholes$blue(context.blue);
        ClientWorldFogAccess.wormholes$targetBiomeFog(context.targetBiomeFog);
        ClientWorldFogAccess.wormholes$previousBiomeFog(context.previousBiomeFog);
        ClientWorldFogAccess.wormholes$biomeChangedTime(context.biomeChangedTime);
    }

    private static void copyFromStaticFields(FogRendererContext context) {
        context.red = ClientWorldFogAccess.wormholes$red();
        context.green = ClientWorldFogAccess.wormholes$green();
        context.blue = ClientWorldFogAccess.wormholes$blue();
        context.targetBiomeFog = ClientWorldFogAccess.wormholes$targetBiomeFog();
        context.previousBiomeFog = ClientWorldFogAccess.wormholes$previousBiomeFog();
        context.biomeChangedTime = ClientWorldFogAccess.wormholes$biomeChangedTime();
    }
}
