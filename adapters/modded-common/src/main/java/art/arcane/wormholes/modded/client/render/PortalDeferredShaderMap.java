package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.backend.opengl.GlProgram;
import net.irisshaders.iris.pipeline.programs.ShaderKey;

public interface PortalDeferredShaderMap {
    void wormholes$shader(ShaderKey key, GlProgram program);
}
