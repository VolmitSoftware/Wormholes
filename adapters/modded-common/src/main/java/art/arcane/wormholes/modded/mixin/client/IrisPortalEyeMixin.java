package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.IrisExclusiveUniforms", remap = false)
public abstract class IrisPortalEyeMixin {
    @Inject(method = "getEyePosition", at = @At("HEAD"), cancellable = true)
    private static void wormholes$eye(CallbackInfoReturnable<Vector3d> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            Vec3 position = view.camera().position();
            callback.setReturnValue(new Vector3d(position.x, position.y, position.z));
        }
    }

    @Inject(method = "lambda$addIrisExclusiveUniforms$6", at = @At("HEAD"), cancellable = true)
    private static void wormholes$seaLevel(CallbackInfoReturnable<Integer> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(view.environment().world().seaLevel());
        }
    }

    @Inject(method = "getThunderStrength", at = @At("HEAD"), cancellable = true)
    private static void wormholes$thunder(CallbackInfoReturnable<Float> callback) {
        PortalShaderContext.View view = PortalShaderContext.current();
        if (view != null) {
            callback.setReturnValue(view.environment().sky().thunder());
        }
    }
}
