package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PortalDrawScopeTest {
    @Test
    public void failedNestedDrawRestoresParentAttachmentsAndViewportThenOuterAttachments() {
        GpuTextureView previousColor = RenderSystem.outputColorTextureOverride;
        GpuTextureView previousDepth = RenderSystem.outputDepthTextureOverride;
        RenderTarget parent = target();
        RenderTarget child = target();
        PortalViewport parentViewport = new PortalViewport(64, 128, 256, 192);
        PortalViewport childViewport = new PortalViewport(128, 128, 64, 64);
        assertNull(PortalDrawScope.current());
        try (PortalDrawScope outer = new PortalDrawScope(parent, parentViewport)) {
            assertThrows(IllegalStateException.class, () -> {
                try (PortalDrawScope inner = new PortalDrawScope(child, childViewport)) {
                    assertSame(inner, PortalDrawScope.current());
                    assertSame(child.getColorTextureView(), RenderSystem.outputColorTextureOverride);
                    assertSame(child.getDepthTextureView(), RenderSystem.outputDepthTextureOverride);
                    assertEquals(new RenderPass.RenderArea(128, 128, 64, 64), inner.area());
                    throw new IllegalStateException("Nested drawing failed");
                }
            });
            assertSame(outer, PortalDrawScope.current());
            assertSame(parent.getColorTextureView(), RenderSystem.outputColorTextureOverride);
            assertSame(parent.getDepthTextureView(), RenderSystem.outputDepthTextureOverride);
            assertEquals(new RenderPass.RenderArea(64, 128, 256, 192), outer.area());
        }
        assertNull(PortalDrawScope.current());
        assertSame(previousColor, RenderSystem.outputColorTextureOverride);
        assertSame(previousDepth, RenderSystem.outputDepthTextureOverride);
    }

    private static RenderTarget target() {
        RenderTarget target = mock(RenderTarget.class);
        when(target.getColorTextureView()).thenReturn(mock(GpuTextureView.class));
        when(target.getDepthTextureView()).thenReturn(mock(GpuTextureView.class));
        return target;
    }
}
