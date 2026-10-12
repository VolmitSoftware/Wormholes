package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.iris.IrisShaderStages;
import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.program.ProgramBuilder", remap = false)
public abstract class IrisPortalProgramStageScopeMixin {
    @WrapMethod(method = "begin")
    private static ProgramBuilder wormholes$programStages(String name, String vertex, String geometry, String fragment,
                                                          ImmutableSet<Integer> reservedUnits, Operation<ProgramBuilder> original) {
        try (IrisShaderStages.Scope stages = IrisShaderStages.open()) {
            return original.call(name, vertex, geometry, fragment, reservedUnits);
        }
    }

    @WrapMethod(method = "beginCompute")
    private static ProgramBuilder wormholes$computeStages(String name, String source, ImmutableSet<Integer> reservedUnits,
                                                          Operation<ProgramBuilder> original) {
        try (IrisShaderStages.Scope stages = IrisShaderStages.open()) {
            return original.call(name, source, reservedUnits);
        }
    }
}
