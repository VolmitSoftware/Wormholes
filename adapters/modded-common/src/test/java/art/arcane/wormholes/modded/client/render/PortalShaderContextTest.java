package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class PortalShaderContextTest {
    @Test
    public void nestedFailedDestinationRestoresTheParentWithoutEnteringSourceContext() {
        PortalShaderContext.View parent = view();
        PortalShaderContext.View child = view();
        assertNull(PortalShaderContext.current());
        try (PortalShaderContext outer = new PortalShaderContext(parent)) {
            outer.drawing(true);
            assertThrows(IllegalStateException.class, () -> {
                try (PortalShaderContext inner = new PortalShaderContext(child)) {
                    assertSame(child, PortalShaderContext.current());
                    assertFalse(PortalShaderContext.drawing());
                    throw new IllegalStateException("child shader failed");
                }
            });
            assertSame(parent, PortalShaderContext.current());
            assertTrue(PortalShaderContext.drawing());
        }
        assertNull(PortalShaderContext.current());
        assertFalse(PortalShaderContext.drawing());
    }

    @Test
    public void sectionWorkersCannotInheritTheRenderThreadsDestination() {
        try (PortalShaderContext context = new PortalShaderContext(view())) {
            context.drawing(true);
            CompletableFuture.runAsync(() -> {
                assertNull(PortalShaderContext.current());
                assertFalse(PortalShaderContext.drawing());
            }).join();
            assertTrue(PortalShaderContext.drawing());
        }
    }

    private static PortalShaderContext.View view() {
        return new PortalShaderContext.View(PortalEnvironmentTest.environment(PortalEnvironmentTest.identity()),
            mock(Camera.class), mock(RenderTarget.class), new Matrix4f(), new Matrix4f());
    }
}
