package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPrograms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.IrisLodRenderProgram", remap = false)
public abstract class IrisPortalDistantTerrainProgramMixin {
    @Shadow
    @Final
    private int id;

    @Unique
    private IrisClipPrograms.DistantBinding wormholes$clipping;

    @Inject(method = "bind", at = @At("RETURN"))
    private void wormholes$clip(CallbackInfo callback) {
        wormholes$applyClip();
    }

    @Inject(method = "fillUniformData", at = @At("RETURN"))
    private void wormholes$clipUniforms(CallbackInfo callback) {
        wormholes$applyClip();
    }

    @Inject(method = "unbind", at = @At("RETURN"))
    private void wormholes$unclip(CallbackInfo callback) {
        IrisClipPlanes.release();
    }

    @Unique
    private void wormholes$applyClip() {
        if (wormholes$clipping == null) {
            wormholes$clipping = IrisClipPrograms.bindDistant(id);
        }
        IrisClipPrograms.applyDistant(wormholes$clipping);
    }
}
