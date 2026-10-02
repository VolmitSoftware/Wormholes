package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.backend.opengl.VertexArray;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PortalIrisOverridesTest {
    @Test
    public void disposingDestinationOverridesClosesEachVaoOnceAndPreservesSourcePrograms() throws ClassNotFoundException {
        GlProgram destination = mock(GlProgram.class);
        GlProgram source = mock(GlProgram.class);
        VertexArray destinationArray = (VertexArray) mock(Class.forName(VertexArray.class.getName() + "$Emulated"));
        VertexArray sourceArray = (VertexArray) mock(Class.forName(VertexArray.class.getName() + "$Emulated"));
        FrontendRenderPipeline own = pipeline(destinationArray);
        FrontendRenderPipeline root = pipeline(sourceArray);
        List<VertexFormat> format = List.of(PortalTerrainVertices.FORMAT);
        Map<CompiledRenderPipeline, Map<GlProgram, Map<List<VertexFormat>, FrontendRenderPipeline>>> cache = new HashMap<>();
        Map<GlProgram, Map<List<VertexFormat>, FrontendRenderPipeline>> mixed = new HashMap<>();
        mixed.put(destination, new HashMap<>(Map.of(format, own)));
        mixed.put(source, new HashMap<>(Map.of(format, root)));
        CompiledRenderPipeline first = mock(CompiledRenderPipeline.class);
        CompiledRenderPipeline second = mock(CompiledRenderPipeline.class);
        cache.put(first, mixed);
        cache.put(second, new HashMap<>(Map.of(destination, new HashMap<>(Map.of(format, own)))));
        PortalIrisOverrides.release(cache, Set.of(destination));
        assertEquals(1, cache.size());
        assertTrue(cache.get(first).containsKey(source));
        assertSame(root, cache.get(first).get(source).get(format));
        verify(destinationArray).close();
        verify(sourceArray, never()).close();
        verify(own, never()).close();
        verify(root, never()).close();
        verify(destination, never()).close();
        verify(source, never()).close();
    }

    private static FrontendRenderPipeline pipeline(VertexArray array) {
        FrontendRenderPipeline frontend = mock(FrontendRenderPipeline.class);
        GlRenderPipeline backend = mock(GlRenderPipeline.class);
        when(frontend.backendRenderPipeline()).thenReturn(backend);
        when(backend.vertexArray()).thenReturn(array);
        return frontend;
    }
}
