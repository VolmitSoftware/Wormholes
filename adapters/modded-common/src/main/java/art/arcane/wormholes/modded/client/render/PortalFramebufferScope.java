package art.arcane.wormholes.modded.client.render;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

public final class PortalFramebufferScope implements AutoCloseable {
    private static final Bindings OPEN_GL = new OpenGlBindings();

    private final Bindings bindings;
    private final int drawFramebuffer;
    private final int readFramebuffer;
    private final int readBuffer;
    private final int[] drawBuffers;
    private final int[] viewport = new int[4];

    PortalFramebufferScope(Bindings bindings) {
        this.bindings = bindings;
        drawFramebuffer = bindings.integer(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        readFramebuffer = bindings.integer(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        readBuffer = bindings.integer(GL11C.GL_READ_BUFFER);
        bindings.integers(GL11C.GL_VIEWPORT, viewport);
        drawBuffers = new int[drawFramebuffer == 0 ? 1 : bindings.integer(GL20C.GL_MAX_DRAW_BUFFERS)];
        for (int index = 0; index < drawBuffers.length; index++) {
            drawBuffers[index] = bindings.integer(GL20C.GL_DRAW_BUFFER0 + index);
        }
    }

    public static PortalFramebufferScope capture() {
        return PortalShaderScope.shaders() ? new PortalFramebufferScope(OPEN_GL) : null;
    }

    @Override
    public void close() {
        bindings.framebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        bindings.drawBuffers(drawBuffers);
        bindings.framebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        bindings.readBuffer(readBuffer);
        bindings.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    interface Bindings {
        int integer(int parameter);

        void integers(int parameter, int[] values);

        void framebuffer(int target, int framebuffer);

        void drawBuffers(int[] buffers);

        void readBuffer(int buffer);

        void viewport(int x, int y, int width, int height);
    }

    private static final class OpenGlBindings implements Bindings {
        @Override
        public int integer(int parameter) {
            return GL11C.glGetInteger(parameter);
        }

        @Override
        public void integers(int parameter, int[] values) {
            GL11C.glGetIntegerv(parameter, values);
        }

        @Override
        public void framebuffer(int target, int framebuffer) {
            GlStateManager._glBindFramebuffer(target, framebuffer);
        }

        @Override
        public void drawBuffers(int[] buffers) {
            GL20C.glDrawBuffers(buffers);
        }

        @Override
        public void readBuffer(int buffer) {
            GL11C.glReadBuffer(buffer);
        }

        @Override
        public void viewport(int x, int y, int width, int height) {
            GlStateManager._viewport(x, y, width, height);
        }
    }
}
