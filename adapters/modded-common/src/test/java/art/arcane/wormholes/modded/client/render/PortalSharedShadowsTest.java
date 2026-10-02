package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class PortalSharedShadowsTest {
    @Test
    public void firstBeginFramebuffersAreOwnedSeparatelyFromSharedDepthAndEarlierViews() throws ReflectiveOperationException {
        GlFramebuffer sharedDepth = mock(GlFramebuffer.class);
        GlFramebuffer sharedCopy = mock(GlFramebuffer.class);
        GlFramebuffer constructor = mock(GlFramebuffer.class);
        GlFramebuffer firstBegin = mock(GlFramebuffer.class);
        ArrayList<GlFramebuffer> owned = new ArrayList<>(List.of(sharedDepth, sharedCopy, constructor));
        PortalSharedShadows shadows = shadows(owned);
        List<GlFramebuffer> acquired;
        try (PortalSharedShadows.Construction capture = shadows.constructing()) {
            owned.add(firstBegin);
            acquired = capture.framebuffers();
        }
        assertEquals(List.of(firstBegin), acquired);
        shadows.release(acquired);
        assertEquals(List.of(sharedDepth, sharedCopy, constructor), owned);
        verify(firstBegin).destroy();
        verify(constructor, never()).destroy();
        verify(sharedDepth, never()).destroy();
        verify(sharedCopy, never()).destroy();
    }

    @Test
    public void failedFramebufferDeletionStillReleasesOtherOwnedFramebuffers() throws ReflectiveOperationException {
        GlFramebuffer broken = mock(GlFramebuffer.class);
        GlFramebuffer remaining = mock(GlFramebuffer.class);
        doThrow(new IllegalStateException("deletion failed")).when(broken).destroy();
        ArrayList<GlFramebuffer> owned = new ArrayList<>(List.of(broken, remaining));
        PortalSharedShadows shadows = shadows(owned);
        assertThrows(IllegalStateException.class, () -> shadows.release(List.of(broken, remaining)));
        assertEquals(List.of(), owned);
        verify(remaining).destroy();
        shadows.release(List.of(broken, remaining));
        verify(broken).destroy();
    }

    private static PortalSharedShadows shadows(List<GlFramebuffer> framebuffers) throws ReflectiveOperationException {
        ShadowRenderTargets target = mock(ShadowRenderTargets.class, withSettings().extraInterfaces(PortalShadowTargets.class));
        when(((PortalShadowTargets) target).wormholes$framebuffers()).thenReturn(framebuffers);
        PortalSharedShadows shadows = new PortalSharedShadows();
        Field field = PortalSharedShadows.class.getDeclaredField("target");
        field.setAccessible(true);
        field.set(shadows, target);
        return shadows;
    }
}
