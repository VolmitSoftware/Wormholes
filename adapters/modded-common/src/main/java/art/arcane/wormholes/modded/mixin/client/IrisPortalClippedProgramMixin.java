/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: MixinIrisSodiumShader binding the clip uniform of every Iris world program and enabling only that
 * program's clip distance while a portal layer draws.
 */
package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPrograms;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Pseudo
@Mixin(targets = {"net.irisshaders.iris.pipeline.programs.ExtendedShader",
    "net.irisshaders.iris.pipeline.programs.FallbackShader"}, remap = false)
public abstract class IrisPortalClippedProgramMixin {
    @Unique
    private IrisClipPrograms.Binding wormholes$clipping;

    @Inject(method = "iris$setupState", at = @At("RETURN"))
    private void wormholes$clip(List<BindGroupLayout.UniformDescription> samplers, CallbackInfo callback) {
        if (wormholes$clipping == null) {
            wormholes$clipping = IrisClipPrograms.bind(((GlProgram) (Object) this).getProgramId());
        }
        IrisClipPlanes.apply(wormholes$clipping.location(), wormholes$clipping.distance());
    }

    @Inject(method = "iris$clearState", at = @At("RETURN"))
    private void wormholes$unclip(CallbackInfo callback) {
        IrisClipPlanes.release();
    }
}
