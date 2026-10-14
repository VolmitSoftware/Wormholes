package art.arcane.wormholes.modded.client.render.stencil;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.TextureFormat;
import org.junit.Test;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class PortalStencilTest {
    private static final int DEPTH = GL30C.GL_DEPTH_COMPONENT32F;
    private static final int DEPTH_EXTERNAL = GL11C.GL_DEPTH_COMPONENT;
    private static final int DEPTH_TYPE = GL11C.GL_FLOAT;
    private static final int COLOR = GL11C.GL_RGBA8;

    @Test
    public void unrequestedDepthTexturesKeepTheirDepthOnlyFormatAndNeverJoinTheStencilSet() {
        try (MockedStatic<PortalBackends> backends = stencilBackend(true)) {
            GlTexture depth = texture(41, TextureFormat.DEPTH32);
            assertEquals(DEPTH, PortalStencil.internalFormat(TextureFormat.DEPTH32, DEPTH));
            assertEquals(DEPTH_EXTERNAL, PortalStencil.externalFormat(TextureFormat.DEPTH32, DEPTH_EXTERNAL));
            assertEquals(DEPTH_TYPE, PortalStencil.pixelType(TextureFormat.DEPTH32, DEPTH_TYPE));
            PortalStencil.created(depth);
            assertFalse(PortalStencil.available(target(depth)));
        }
    }

    @Test
    public void requestedTargetDepthGetsStencilAndJoinsTheStencilSetUntilDestroyed() {
        try (MockedStatic<PortalBackends> backends = stencilBackend(true)) {
            GlTexture depth = texture(42, TextureFormat.DEPTH32);
            GlTexture color = texture(43, TextureFormat.RGBA8);
            PortalStencil.createTarget(true, () -> {
                assertEquals(GL30C.GL_DEPTH32F_STENCIL8, PortalStencil.internalFormat(TextureFormat.DEPTH32, DEPTH));
                assertEquals(GL30C.GL_DEPTH_STENCIL, PortalStencil.externalFormat(TextureFormat.DEPTH32, DEPTH_EXTERNAL));
                assertEquals(GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV, PortalStencil.pixelType(TextureFormat.DEPTH32, DEPTH_TYPE));
                assertEquals(COLOR, PortalStencil.internalFormat(TextureFormat.RGBA8, COLOR));
                PortalStencil.created(depth);
                PortalStencil.created(color);
            });
            assertEquals(DEPTH, PortalStencil.internalFormat(TextureFormat.DEPTH32, DEPTH));
            assertTrue(PortalStencil.available(target(depth)));
            assertFalse(PortalStencil.available(target(color)));
            PortalStencil.destroyed(42);
            assertFalse(PortalStencil.available(target(depth)));
        }
    }

    @Test
    public void unstenciledTargetsAndBackendsWithoutStencilKeepDepthOnlyFormats() {
        try (MockedStatic<PortalBackends> backends = stencilBackend(true)) {
            PortalStencil.createTarget(false, () -> assertEquals(DEPTH, PortalStencil.internalFormat(TextureFormat.DEPTH32, DEPTH)));
        }
        try (MockedStatic<PortalBackends> backends = stencilBackend(false)) {
            PortalStencil.createTarget(true, () -> assertEquals(DEPTH, PortalStencil.internalFormat(TextureFormat.DEPTH32, DEPTH)));
        }
    }

    @Test
    public void failedTargetCreationEndsTheStencilRequest() {
        try (MockedStatic<PortalBackends> backends = stencilBackend(true)) {
            assertThrows(IllegalStateException.class, () -> PortalStencil.createTarget(true, () -> {
                throw new IllegalStateException("OpenGL error 1285");
            }));
            assertEquals(DEPTH, PortalStencil.internalFormat(TextureFormat.DEPTH32, DEPTH));
        }
    }

    @Test
    public void onlyTheMainTargetAndNumberedPortalLayersRequestStencil() {
        assertTrue(PortalStencil.stencilTarget(mock(MainTarget.class), "Main"));
        assertTrue(PortalStencil.stencilTarget(mock(RenderTarget.class), "Wormholes portal layer 2"));
        assertFalse(PortalStencil.stencilTarget(mock(RenderTarget.class), "Wormholes portal layer"));
        assertFalse(PortalStencil.stencilTarget(mock(RenderTarget.class), "Entity Outline"));
    }

    private static MockedStatic<PortalBackends> stencilBackend(boolean stencil) {
        MockedStatic<PortalBackends> backends = mockStatic(PortalBackends.class);
        backends.when(PortalBackends::stencilBuffer).thenReturn(stencil);
        return backends;
    }

    private static GlTexture texture(int id, TextureFormat format) {
        GlTexture texture = mock(GlTexture.class);
        when(texture.glId()).thenReturn(id);
        when(texture.getFormat()).thenReturn(format);
        return texture;
    }

    private static RenderTarget target(GlTexture depth) {
        RenderTarget target = mock(RenderTarget.class);
        when(target.getDepthTexture()).thenReturn(depth);
        return target;
    }
}
