/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: MixinIrisSodiumShader binding the clip uniform of Iris's Sodium terrain programs and enabling only that
 * program's clip distance while a portal layer draws.
 */
package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPrograms;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumShader", remap = false)
public abstract class IrisPortalSodiumProgramMixin {
    @Unique
    private IrisClipPrograms.Binding wormholes$clipping;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wormholes$bind(CallbackInfo callback, @Local(argsOnly = true) int program) {
        wormholes$clipping = IrisClipPrograms.bind(program);
    }

    @Inject(method = "setupState", at = @At("RETURN"))
    private void wormholes$clip(CallbackInfo callback) {
        IrisClipPlanes.apply(wormholes$clipping.location(), wormholes$clipping.distance());
    }

    @Inject(method = "resetState", at = @At("RETURN"))
    private void wormholes$unclip(CallbackInfo callback) {
        IrisClipPlanes.release();
    }
}
