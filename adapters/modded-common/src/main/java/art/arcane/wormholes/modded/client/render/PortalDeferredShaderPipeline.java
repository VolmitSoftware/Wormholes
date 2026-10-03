package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;

public interface PortalDeferredShaderPipeline {
    void wormholes$shader(ShaderKey key, GlProgram program);

    void wormholes$finishShaders(ProgramSet programs);

    void wormholes$resetShaders();

    boolean wormholes$hasShadows();
}
