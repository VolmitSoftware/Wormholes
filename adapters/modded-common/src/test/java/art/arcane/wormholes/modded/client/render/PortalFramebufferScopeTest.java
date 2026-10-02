package art.arcane.wormholes.modded.client.render;

import org.junit.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PortalFramebufferScopeTest {
    @Test
    public void restoresSourceReadAndDrawFramebufferAttachmentsAndViewportAfterFailure() {
        PortalFramebufferScope.Bindings bindings = mock(PortalFramebufferScope.Bindings.class);
        when(bindings.integer(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)).thenReturn(27);
        when(bindings.integer(GL30C.GL_READ_FRAMEBUFFER_BINDING)).thenReturn(19);
        when(bindings.integer(GL11C.GL_READ_BUFFER)).thenReturn(GL30C.GL_COLOR_ATTACHMENT2);
        when(bindings.integer(GL20C.GL_MAX_DRAW_BUFFERS)).thenReturn(3);
        when(bindings.integer(GL20C.GL_DRAW_BUFFER0)).thenReturn(GL30C.GL_COLOR_ATTACHMENT0);
        when(bindings.integer(GL20C.GL_DRAW_BUFFER0 + 1)).thenReturn(GL11C.GL_NONE);
        when(bindings.integer(GL20C.GL_DRAW_BUFFER0 + 2)).thenReturn(GL30C.GL_COLOR_ATTACHMENT2);
        doAnswer(call -> {
            int[] viewport = call.getArgument(1);
            System.arraycopy(new int[]{5, 7, 1440, 900}, 0, viewport, 0, 4);
            return null;
        }).when(bindings).integers(eq(GL11C.GL_VIEWPORT), any(int[].class));
        assertThrows(IllegalStateException.class, () -> {
            try (PortalFramebufferScope scope = new PortalFramebufferScope(bindings)) {
                throw new IllegalStateException("destination render failed");
            }
        });
        verify(bindings).framebuffer(GL30C.GL_DRAW_FRAMEBUFFER, 27);
        verify(bindings).framebuffer(GL30C.GL_READ_FRAMEBUFFER, 19);
        verify(bindings).viewport(5, 7, 1440, 900);
        verify(bindings).drawBuffers(new int[]{GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_NONE, GL30C.GL_COLOR_ATTACHMENT2});
        verify(bindings).readBuffer(GL30C.GL_COLOR_ATTACHMENT2);
    }

    @Test
    public void defaultFramebufferRestoresOnlyItsSingleDrawBuffer() {
        PortalFramebufferScope.Bindings bindings = mock(PortalFramebufferScope.Bindings.class);
        when(bindings.integer(GL20C.GL_DRAW_BUFFER0)).thenReturn(GL11C.GL_BACK);
        try (PortalFramebufferScope scope = new PortalFramebufferScope(bindings)) {
            verify(bindings).integer(GL20C.GL_DRAW_BUFFER0);
        }
        verify(bindings).drawBuffers(new int[]{GL11C.GL_BACK});
    }
}
