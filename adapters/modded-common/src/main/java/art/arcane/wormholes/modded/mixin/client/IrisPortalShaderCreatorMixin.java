package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisShaderStages;
import art.arcane.wormholes.modded.client.render.PortalIrisClipCompilation;
import art.arcane.wormholes.modded.client.render.PortalIrisClipping;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;

import java.util.EnumMap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class IrisPortalShaderCreatorMixin {
    @WrapMethod(method = "link")
    private static PartialShader wormholes$clip(String name, String vertex, String geometry, String tessControl,
                                              String tessEval, String fragment, VertexFormat format,
                                              boolean fallback, Operation<PartialShader> original) {
        if (!PortalIrisClipCompilation.active()) {
            return original.call(name, vertex, geometry, tessControl, tessEval, fragment, format, fallback);
        }
        EnumMap<PatchShaderType, String> sources = new EnumMap<>(PatchShaderType.class);
        sources.put(PatchShaderType.VERTEX, vertex);
        sources.put(PatchShaderType.GEOMETRY, geometry);
        sources.put(PatchShaderType.TESS_CONTROL, tessControl);
        sources.put(PatchShaderType.TESS_EVAL, tessEval);
        sources.put(PatchShaderType.FRAGMENT, fragment);
        PortalIrisClipping.Result clipping = PortalIrisClipCompilation.transform(sources);
        if (clipping == null) {
            return original.call(name, vertex, geometry, tessControl, tessEval, fragment, format, fallback);
        }
        return original.call(name, clipping.sources().get(PatchShaderType.VERTEX),
            clipping.sources().get(PatchShaderType.GEOMETRY), clipping.sources().get(PatchShaderType.TESS_CONTROL),
            clipping.sources().get(PatchShaderType.TESS_EVAL), clipping.sources().get(PatchShaderType.FRAGMENT),
            format, fallback);
    }

    @WrapMethod(method = "createShader")
    private static int wormholes$compile(String name, ShaderType type, String source, Operation<Integer> original) {
        return PortalIrisShaderStages.compile(type.id, source, () -> original.call(name, type, source));
    }

    @WrapOperation(method = "detachIfValid", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glDeleteShader(I)V"))
    private static void wormholes$delete(int handle, Operation<Void> original) {
        PortalIrisShaderStages.delete(handle);
    }

    @WrapOperation(method = "link", at = @At(value = "INVOKE",
        target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;glLinkProgram(I)V"))
    private static void wormholes$link(int program, Operation<Void> original) {
        long started = System.nanoTime();
        try {
            original.call(program);
        } finally {
            PortalIrisShaderStages.linkTime(System.nanoTime() - started);
        }
    }
}
