package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.IrisExclusiveUniforms$WorldInfoUniforms", remap = false)
public abstract class IrisPortalWorldInfoMixin {
    @Inject(method = "addWorldInfoUniforms", at = @At("HEAD"), cancellable = true)
    private static void wormholes$world(UniformHolder uniforms, CallbackInfo callback) {
        if (PortalShaderContext.current() == null) {
            return;
        }
        uniforms.uniform1i(UniformUpdateFrequency.PER_FRAME, "bedrockLevel", () -> PortalShaderContext.current().environment().dimension().minY())
            .uniform1f(UniformUpdateFrequency.PER_FRAME, "cloudHeight", () -> PortalShaderContext.current().environment().clouds().height())
            .uniform1i(UniformUpdateFrequency.PER_FRAME, "heightLimit", () -> PortalShaderContext.current().environment().dimension().height())
            .uniform1i(UniformUpdateFrequency.PER_FRAME, "logicalHeightLimit", () -> PortalShaderContext.current().environment().world().logicalHeight())
            .uniform1b(UniformUpdateFrequency.PER_FRAME, "hasCeiling", () -> PortalShaderContext.current().environment().world().hasCeiling())
            .uniform1b(UniformUpdateFrequency.PER_FRAME, "hasSkylight", () -> PortalShaderContext.current().environment().dimension().hasSkyLight())
            .uniform1f(UniformUpdateFrequency.PER_FRAME, "ambientLight", () -> PortalShaderContext.current().environment().world().ambientLight());
        callback.cancel();
    }
}
