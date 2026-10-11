package art.arcane.wormholes.clientgametest.vanillamixin;

import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice", remap = false)
public abstract class VanillaPipelinePreparationNativeTap {
    @WrapOperation(method = "compileShader(Lcom/mojang/blaze3d/opengl/GlDevice$ShaderCompilationKey;Lcom/mojang/blaze3d/shaders/ShaderSource;)Lcom/mojang/blaze3d/opengl/GlShaderModule;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;glCompileShader(I)V"), require = 1, allow = 1)
    private void wormholesTest$compileShader(int shader, Operation<Void> original) {
        long started = VanillaPipelinePreparationTap.shaderCompileStarted();
        try {
            original.call(shader);
        } finally {
            VanillaPipelinePreparationTap.nativeCallFinished("glCompileShader", started);
        }
    }

    @WrapOperation(method = "compileProgram(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/mojang/blaze3d/shaders/ShaderSource;)Lcom/mojang/blaze3d/opengl/GlProgram;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlProgram;link(Lcom/mojang/blaze3d/opengl/GlShaderModule;Lcom/mojang/blaze3d/opengl/GlShaderModule;[Lcom/mojang/blaze3d/vertex/VertexFormat;Ljava/lang/String;)Lcom/mojang/blaze3d/opengl/GlProgram;"), require = 1, allow = 1)
    private GlProgram wormholesTest$linkRoute(GlShaderModule vertex, GlShaderModule fragment, VertexFormat[] formats,
                                            String label, Operation<GlProgram> original) {
        VanillaPipelinePreparationTap.programLinkRoute();
        return original.call(vertex, fragment, formats, label);
    }

    @Mixin(value = GlProgram.class, remap = false)
    public abstract static class Program {
        @WrapOperation(method = "link(Lcom/mojang/blaze3d/opengl/GlShaderModule;Lcom/mojang/blaze3d/opengl/GlShaderModule;[Lcom/mojang/blaze3d/vertex/VertexFormat;Ljava/lang/String;)Lcom/mojang/blaze3d/opengl/GlProgram;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;glLinkProgram(I)V"), require = 1, allow = 1)
        private static void wormholesTest$linkProgram(int program, Operation<Void> original) {
            long started = VanillaPipelinePreparationTap.programLinkStarted();
            try {
                original.call(program);
            } finally {
                VanillaPipelinePreparationTap.nativeCallFinished("glLinkProgram", started);
            }
        }

        @Inject(method = "close()V", at = @At("RETURN"), require = 1, allow = 1)
        private void wormholesTest$closed(CallbackInfo callback) {
            GlProgram program = (GlProgram) (Object) this;
            VanillaPipelinePreparationTap.programClosed(program, program.getProgramId());
        }
    }

    @Mixin(value = GameRenderer.class, remap = false)
    public abstract static class Frames {
        @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"), require = 1, allow = 1)
        private void wormholesTest$frame(CallbackInfo callback) {
            VanillaPipelinePreparationTap.renderedFrame();
        }
    }
}
