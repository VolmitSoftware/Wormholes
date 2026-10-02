package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PortalPipelinesTest {
    @Test
    public void nativePipelineBuildersKeepCustomNamespace() {
        for (String name : new String[] {"solid", "cutout", "translucent", "solid_reflected", "cutout_reflected", "translucent_reflected"}) {
            verify(PortalPipelines.terrain(name, RenderPipelines.SOLID_BLOCK, false), "portal_" + name);
            verify(PortalPipelines.terrain(name, RenderPipelines.SOLID_BLOCK, true), "portal_" + name);
        }
        verify(PortalPipelines.compositePipeline(), "portal_composite");
        verify(PortalPipelines.layerPipeline(), "portal_layer");
    }

    private static void verify(RenderPipeline pipeline, String path) {
        assertEquals("wormholes", pipeline.getLocation().getNamespace());
        assertEquals(path, pipeline.getLocation().getPath());
        for (Identifier shader : pipeline.getShaders().values()) {
            assertEquals("wormholes", shader.getNamespace());
        }
    }
}
