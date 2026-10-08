/*
 * Derived from Immersive Portals (https://github.com/iPortalTeam/ImmersivePortalsMod),
 * Copyright 2020 qouteall, licensed under the Apache License, Version 2.0.
 * Modified for Wormholes: MixinIrisTransformPatcher rewriting every clipped program Iris transforms, not only Sodium terrain.
 */
package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisClipPrograms;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class IrisPortalTransformMixin {
    @Inject(method = "transformInternal", at = @At("RETURN"), cancellable = true)
    private static void wormholes$clipPortalLayers(String name, Map<PatchShaderType, String> inputs, Parameters parameters,
                                                   CallbackInfoReturnable<Map<PatchShaderType, String>> callback) {
        Map<PatchShaderType, String> clipped = IrisClipPrograms.transform(name, parameters, callback.getReturnValue());
        if (clipped != null) {
            callback.setReturnValue(clipped);
        }
    }
}
