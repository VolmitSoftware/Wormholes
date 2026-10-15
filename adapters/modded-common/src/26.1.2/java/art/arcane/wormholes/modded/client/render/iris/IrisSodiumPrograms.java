package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.mixin.client.IrisPortalShaderCreatorAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalSodiumProgramsAccess;
import com.mojang.blaze3d.opengl.GlStateManager;
import net.caffeinemc.mods.sodium.client.gl.shader.GlProgram;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderBindingPoints;
import net.caffeinemc.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import net.irisshaders.iris.gl.shader.ShaderCompileException;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import org.lwjgl.opengl.GL20C;

import java.util.EnumMap;

public final class IrisSodiumPrograms {
    private static final int ATTRIBUTE_NORMAL = 10;
    private static final int ATTRIBUTE_ENTITY = 11;
    private static final int ATTRIBUTE_MID_TEXTURE = 12;
    private static final int ATTRIBUTE_TANGENT = 13;
    private static final int ATTRIBUTE_MID_BLOCK = 14;

    private IrisSodiumPrograms() {
    }

    public static void destroy(SodiumPrograms programs) {
        EnumMap<SodiumPrograms.Pass, GlProgram<ChunkShaderInterface>> shaders = ((IrisPortalSodiumProgramsAccess) programs).wormholes$shaders();
        for (GlProgram<ChunkShaderInterface> program : shaders.values()) {
            program.delete();
        }
        shaders.clear();
    }

    static int link(IrisProgramReserve.Inputs inputs) {
        IrisProgramReserve.Stages stages = inputs.stages();
        try (IrisShaderStages.Scope scope = IrisShaderStages.open()) {
            int program = IrisShaderStages.createdProgram(GlStateManager.glCreateProgram());
            if (program <= 0) {
                throw new IllegalStateException("Could not create the Sodium terrain program " + inputs.name());
            }
            attach(program, inputs.name(), ShaderType.VERTEX, stages.vertex());
            attach(program, inputs.name(), ShaderType.GEOMETRY, stages.geometry());
            attach(program, inputs.name(), ShaderType.TESSELATION_CONTROL, stages.tessControl());
            attach(program, inputs.name(), ShaderType.TESSELATION_EVAL, stages.tessEval());
            attach(program, inputs.name(), ShaderType.FRAGMENT, stages.fragment());
            bindAttributes(program);
            GlStateManager.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL20C.GL_TRUE) {
                throw new ShaderCompileException(inputs.name(), GL20C.glGetProgramInfoLog(program));
            }
            scope.linked(program);
            return program;
        }
    }

    private static void attach(int program, String name, ShaderType type, String source) {
        if (source != null) {
            GlStateManager.glAttachShader(program, IrisPortalShaderCreatorAccess.wormholes$createShader(name, type, source));
        }
    }

    private static void bindAttributes(int program) {
        GL20C.glBindAttribLocation(program, ChunkShaderBindingPoints.ATTRIBUTE_POSITION, "a_Position");
        GL20C.glBindAttribLocation(program, ChunkShaderBindingPoints.ATTRIBUTE_COLOR, "a_Color");
        GL20C.glBindAttribLocation(program, ChunkShaderBindingPoints.ATTRIBUTE_TEXTURE, "a_TexCoord");
        GL20C.glBindAttribLocation(program, ChunkShaderBindingPoints.ATTRIBUTE_LIGHT_MATERIAL_INDEX, "a_LightAndData");
        GL20C.glBindAttribLocation(program, ATTRIBUTE_ENTITY, "mc_Entity");
        GL20C.glBindAttribLocation(program, ATTRIBUTE_MID_TEXTURE, "mc_midTexCoord");
        GL20C.glBindAttribLocation(program, ATTRIBUTE_TANGENT, "at_tangent");
        GL20C.glBindAttribLocation(program, ATTRIBUTE_NORMAL, "iris_Normal");
        GL20C.glBindAttribLocation(program, ATTRIBUTE_MID_BLOCK, "at_midBlock");
    }
}
