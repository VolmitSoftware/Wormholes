package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisProgramReserves;
import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.gl.shader.GlShader;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumPrograms", remap = false)
public abstract class IrisPortalSodiumReserveMixin {
    @Unique
    private int wormholes$reserved;

    @WrapMethod(method = "createGlShaders")
    private Map<PatchShaderType, GlShader> wormholes$reservedStages(String passName, Map<PatchShaderType, String> transformed,
                                                                    Operation<Map<PatchShaderType, GlShader>> original) {
        int reserved = IrisProgramReserves.sodium(passName, transformed.get(PatchShaderType.VERTEX), transformed.get(PatchShaderType.GEOMETRY),
            transformed.get(PatchShaderType.TESS_CONTROL), transformed.get(PatchShaderType.TESS_EVAL), transformed.get(PatchShaderType.FRAGMENT));
        if (reserved <= 0) {
            return original.call(passName, transformed);
        }
        wormholes$reserved = reserved;
        return new EnumMap<>(PatchShaderType.class);
    }

    @WrapOperation(method = "buildProgram", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/gl/shader/GlProgram$Builder;link(Ljava/util/function/Function;)Lnet/caffeinemc/mods/sodium/client/gl/shader/GlProgram;"))
    private GlProgram<?> wormholes$reservedProgram(GlProgram.Builder builder, Function<ShaderBindingContext, ?> factory,
                                                   Operation<GlProgram<?>> original) {
        int reserved = wormholes$reserved;
        if (reserved <= 0) {
            return original.call(builder, factory);
        }
        wormholes$reserved = 0;
        GL20C.glDeleteProgram(((IrisPortalSodiumBuilderAccess) builder).wormholes$program());
        try {
            return IrisPortalSodiumProgramAccess.wormholes$create(reserved, factory);
        } catch (RuntimeException | Error failure) {
            GL20C.glDeleteProgram(reserved);
            throw failure;
        }
    }

    @WrapMethod(method = "createShader")
    private GlProgram<ChunkShaderInterface> wormholes$releaseReserved(IrisRenderingPipeline pipeline, SodiumPrograms.Pass pass, ProgramSource source,
                                                                      AlphaTest alphaTest, CustomUniforms customUniforms,
                                                                      Supplier<ImmutableSet<Integer>> flipState,
                                                                      Map<PatchShaderType, GlShader> transformed,
                                                                      Operation<GlProgram<ChunkShaderInterface>> original) {
        try {
            return original.call(pipeline, pass, source, alphaTest, customUniforms, flipState, transformed);
        } finally {
            if (wormholes$reserved > 0) {
                GL20C.glDeleteProgram(wormholes$reserved);
                wormholes$reserved = 0;
            }
        }
    }
}
