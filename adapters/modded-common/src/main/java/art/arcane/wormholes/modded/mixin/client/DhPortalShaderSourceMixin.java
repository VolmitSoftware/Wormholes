package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhClipTransformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShader", remap = false)
public abstract class DhPortalShaderSourceMixin {
    @Inject(method = "loadFile", at = @At("RETURN"), cancellable = true)
    private static void wormholes$clipVertex(String path, boolean absolute, CallbackInfoReturnable<String> callback) {
        if (!absolute) {
            callback.setReturnValue(DhClipTransformation.transform(path, callback.getReturnValue()));
        }
    }
}
