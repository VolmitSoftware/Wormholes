package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.ShaderAllocationTap;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = GlStateManager.class, remap = false)
public abstract class IrisShaderAllocationTap {
    @Inject(method = "glCreateShader", at = @At("RETURN"))
    private static void wormholesTest$createdShader(CallbackInfoReturnable<Integer> callback) {
        ShaderAllocationTap.shader(callback.getReturnValue());
    }

    @Inject(method = "glCreateProgram", at = @At("RETURN"))
    private static void wormholesTest$createdProgram(CallbackInfoReturnable<Integer> callback) {
        ShaderAllocationTap.program(callback.getReturnValue());
    }
}
