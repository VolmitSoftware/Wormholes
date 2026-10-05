package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderSystemAccess;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.backend.opengl.VertexArray;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PortalIrisOverrides {
    private PortalIrisOverrides() {
    }

    static void release(IrisRenderingPipeline pipeline) {
        Set<GlProgram> programs = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ShaderKey key : ShaderKey.values()) {
            GlProgram program = pipeline.getShaderMap().getShader(key);
            if (program != null) {
                programs.add(program);
            }
        }
        release(IrisPortalRenderSystemAccess.wormholes$overrides(), programs);
    }

    static void release(Map<CompiledRenderPipeline, Map<GlProgram, Map<List<VertexFormat>, FrontendRenderPipeline>>> overrides,
                        Set<GlProgram> programs) {
        Set<VertexArray> arrays = Collections.newSetFromMap(new IdentityHashMap<>());
        Iterator<Map<GlProgram, Map<List<VertexFormat>, FrontendRenderPipeline>>> outer = overrides.values().iterator();
        while (outer.hasNext()) {
            Map<GlProgram, Map<List<VertexFormat>, FrontendRenderPipeline>> entries = outer.next();
            for (GlProgram program : programs) {
                Map<List<VertexFormat>, FrontendRenderPipeline> removed = entries.remove(program);
                if (removed != null) {
                    for (FrontendRenderPipeline compiled : removed.values()) {
                        arrays.add(((GlRenderPipeline) compiled.backendRenderPipeline()).vertexArray());
                    }
                }
            }
            if (entries.isEmpty()) {
                outer.remove();
            }
        }
        for (VertexArray array : arrays) {
            array.close();
        }
    }
}
