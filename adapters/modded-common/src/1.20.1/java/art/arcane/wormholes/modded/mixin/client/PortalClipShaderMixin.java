package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.shaders.Program;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** Adds the portal clip distance to every preprocessed core vertex shader, and falls back when the patch breaks one. */
@Mixin(Program.class)
public abstract class PortalClipShaderMixin {
    @WrapOperation(method = "compileShaderInternal(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;)I",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;glShaderSource(ILjava/util/List;)V"))
    private static void wormholes$clipSource(int shader, List<String> source, Operation<Void> original,
                                             @Local(argsOnly = true) Program.Type type, @Local(argsOnly = true, ordinal = 0) String name) {
        original.call(shader, PortalClipShaders.source(shader, type, name, source));
    }

    @WrapOperation(method = "compileShaderInternal(Lcom/mojang/blaze3d/shaders/Program$Type;Ljava/lang/String;Ljava/io/InputStream;Ljava/lang/String;Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;)I",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;glGetShaderi(II)I"))
    private static int wormholes$clipStatus(int shader, int parameter, Operation<Integer> original) {
        return PortalClipShaders.compiled(shader, original.call(shader, parameter));
    }
}
