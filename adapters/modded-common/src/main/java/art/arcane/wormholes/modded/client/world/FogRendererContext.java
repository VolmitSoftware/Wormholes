/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: carries the 26.x atmospheric rain fog state per client level.
 */
package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.mixin.client.ClientWorldAtmosphereAccess;
import art.arcane.wormholes.modded.mixin.client.ClientWorldFogAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.client.renderer.fog.environment.FogEnvironment;

final class FogRendererContext {
    private static final StaticFieldsSwappingManager<FogRendererContext> SWAPPING =
        new StaticFieldsSwappingManager<>(FogRendererContext::copyToStaticFields, FogRendererContext::copyFromStaticFields, FogRendererContext::new);

    private float rainFogMultiplier;

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
        ClientWorldAtmosphereAccess atmosphere = atmosphere();
        if (atmosphere != null) {
            atmosphere.wormholes$rainFogMultiplier(context.rainFogMultiplier);
        }
    }

    private static void copyFromStaticFields(FogRendererContext context) {
        ClientWorldAtmosphereAccess atmosphere = atmosphere();
        if (atmosphere != null) {
            context.rainFogMultiplier = atmosphere.wormholes$rainFogMultiplier();
        }
    }

    private static ClientWorldAtmosphereAccess atmosphere() {
        for (FogEnvironment environment : ClientWorldFogAccess.wormholes$environments()) {
            if (environment instanceof AtmosphericFogEnvironment) {
                return (ClientWorldAtmosphereAccess) environment;
            }
        }
        return null;
    }
}
