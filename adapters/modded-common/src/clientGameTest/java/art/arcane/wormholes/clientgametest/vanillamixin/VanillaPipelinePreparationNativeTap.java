package art.arcane.wormholes.clientgametest.vanillamixin;

import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlPipelineRecompiler;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlShaderModule;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = GlPipelineRecompiler.class, remap = false)
public abstract class VanillaPipelinePreparationNativeTap {
    @WrapOperation(method = "compileShader(Ljava/lang/String;Lcom/mojang/renderpearl/api/pipeline/ShaderType;Ljava/lang/String;)Lcom/mojang/renderpearl/backend/opengl/GlShaderModule;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glCompileShader(I)V"), require = 1, allow = 1)
    private void wormholesTest$compileShader(int shader, Operation<Void> original) {
        long started = VanillaPipelinePreparationTap.shaderCompileStarted();
        try {
            original.call(shader);
        } finally {
            VanillaPipelinePreparationTap.nativeCallFinished("glCompileShader", started);
        }
    }

    @WrapOperation(method = "compileProgram(Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline$CreateInfo;Ljava/util/Map;)Lcom/mojang/renderpearl/backend/opengl/GlProgram;",
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlProgram;link(Ljava/util/List;Ljava/lang/String;)Lcom/mojang/renderpearl/backend/opengl/GlProgram;"), require = 1, allow = 1)
    private GlProgram wormholesTest$linkRoute(List<GlShaderModule> shaders, String label, Operation<GlProgram> original) {
        VanillaPipelinePreparationTap.programLinkRoute();
        return original.call(shaders, label);
    }

    @Mixin(value = GlProgram.class, remap = false)
    public abstract static class Program {
        @WrapOperation(method = "link(Ljava/util/List;Ljava/lang/String;)Lcom/mojang/renderpearl/backend/opengl/GlProgram;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glLinkProgram(I)V"), require = 1, allow = 1)
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
        @Inject(method = "render()V", at = @At("RETURN"), require = 1, allow = 1)
        private void wormholesTest$frame(CallbackInfo callback) {
            VanillaPipelinePreparationTap.renderedFrame();
        }
    }
}
