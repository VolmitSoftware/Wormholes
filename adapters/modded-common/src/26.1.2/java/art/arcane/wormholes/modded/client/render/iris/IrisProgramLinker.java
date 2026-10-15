package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.mixin.client.IrisPortalProgramBuilderAccess;
import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.irisshaders.iris.gl.program.ProgramBuilder;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;

final class IrisProgramLinker {
    private IrisProgramLinker() {
    }

    static int link(IrisProgramReserve.Inputs inputs) {
        IrisProgramReserve.Stages stages = inputs.stages();
        return switch (inputs.kind()) {
            case SHADER -> ShaderCreator.link(inputs.name(), stages.vertex(), stages.geometry(), stages.tessControl(), stages.tessEval(),
                stages.fragment(), (VertexFormat) inputs.format(), inputs.fallback()).program();
            case PROGRAM -> program(ProgramBuilder.begin(inputs.name(), stages.vertex(), stages.geometry(), stages.fragment(),
                ImmutableSet.copyOf(inputs.units())));
            case COMPUTE -> program(ProgramBuilder.beginCompute(inputs.name(), stages.compute(), ImmutableSet.copyOf(inputs.units())));
            case SODIUM -> IrisSodiumPrograms.link(inputs);
        };
    }

    private static int program(ProgramBuilder builder) {
        return ((IrisPortalProgramBuilderAccess) builder).wormholes$program();
    }
}
