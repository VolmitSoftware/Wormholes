package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalDeferredShaderCreation;
import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
public abstract class IrisPortalFinalLoadingMixin {
    @Unique private final PortalDeferredShaderCreation<Program> wormholes$programs = new PortalDeferredShaderCreation<>(null);
    @Unique private final PortalDeferredShaderCreation<ComputeProgram[]> wormholes$computes = new PortalDeferredShaderCreation<>(new ComputeProgram[0]);

    @WrapMethod(method = "createProgram")
    private Program wormholes$deferProgram(ProgramSource source, ImmutableSet<Integer> flipped, ImmutableSet<Integer> flippedOnce,
                                          Supplier<ShadowRenderTargets> shadows, Operation<Program> original) {
        return wormholes$programs.create(() -> original.call(source, flipped, flippedOnce, shadows));
    }

    @WrapMethod(method = "createComputes")
    private ComputeProgram[] wormholes$deferComputes(ComputeSource[] sources, ImmutableSet<Integer> flipped, ImmutableSet<Integer> flippedOnce,
                                                    Supplier<ShadowRenderTargets> shadows, ShaderStorageBufferHolder buffers,
                                                    Operation<ComputeProgram[]> original) {
        return wormholes$computes.create(() -> original.call(sources, flipped, flippedOnce, shadows, buffers));
    }

    @WrapOperation(method = "lambda$new$0", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
        target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer$Pass;program:Lnet/irisshaders/iris/gl/program/Program;"))
    private void wormholes$assignProgram(@Coerce Object pass, Program program, Operation<Void> original) {
        wormholes$programs.assign(program, linked -> original.call(pass, linked));
    }

    @WrapOperation(method = "lambda$new$0", at = {
        @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer$Pass;computes:[Lnet/irisshaders/iris/gl/program/ComputeProgram;")
    })
    private void wormholes$assignComputes(@Coerce Object pass, ComputeProgram[] computes, Operation<Void> original) {
        wormholes$computes.assign(computes, linked -> original.call(pass, linked));
    }
}
