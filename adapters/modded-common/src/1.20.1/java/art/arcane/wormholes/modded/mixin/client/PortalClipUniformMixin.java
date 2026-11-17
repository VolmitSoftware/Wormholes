package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Uploads the portal clip plane whenever a core program is applied. */
@Mixin(ShaderInstance.class)
public abstract class PortalClipUniformMixin {
    @Inject(method = "apply()V", at = @At("TAIL"))
    private void wormholes$clipPlane(CallbackInfo callback) {
        PortalClipShaders.apply((ShaderInstance) (Object) this);
    }
}
