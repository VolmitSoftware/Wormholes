package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

public class PortalLightmapScopeTest {
    @Test
    public void nestedDestinationFailureRestoresTheParentLightmap() {
        GpuTextureView parent = mock(GpuTextureView.class);
        GpuTextureView child = mock(GpuTextureView.class);
        assertNull(PortalLightmapScope.current());
        try (PortalLightmapScope outer = new PortalLightmapScope(parent)) {
            try {
                try (PortalLightmapScope inner = new PortalLightmapScope(child)) {
                    assertSame(child, PortalLightmapScope.current());
                    throw new IllegalStateException("child render failed");
                }
            } catch (IllegalStateException expected) {
                assertSame(parent, PortalLightmapScope.current());
            }
        }
        assertNull(PortalLightmapScope.current());
    }
}
