package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class PortalStencilTest {
    @Test
    public void theMainTargetAndNumberedPortalLayersRequestStencilWhenTheirBuffersAreRecreated() {
        assertTrue(PortalStencil.stencilTarget(mock(MainTarget.class), "Main"));
        assertTrue(PortalStencil.stencilTarget(mock(RenderTarget.class), "Wormholes portal layer 2"));
        assertFalse(PortalStencil.stencilTarget(mock(RenderTarget.class), "Wormholes portal layer"));
        assertFalse(PortalStencil.stencilTarget(mock(RenderTarget.class), "Entity Outline"));
    }

    @Test
    public void requestedDepthGainsStencilOnlyOnStencilBackends() {
        try (MockedStatic<PortalBackends> backends = mockStatic(PortalBackends.class)) {
            backends.when(PortalBackends::stencilBuffer).thenReturn(true);
            assertEquals(GpuFormat.D32_FLOAT_S8_UINT, PortalStencil.mainDepthFormat(GpuFormat.D32_FLOAT));
            assertEquals(GpuFormat.RGBA8_UNORM, PortalStencil.mainDepthFormat(GpuFormat.RGBA8_UNORM));
            backends.when(PortalBackends::stencilBuffer).thenReturn(false);
            assertEquals(GpuFormat.D32_FLOAT, PortalStencil.mainDepthFormat(GpuFormat.D32_FLOAT));
        }
    }
}
