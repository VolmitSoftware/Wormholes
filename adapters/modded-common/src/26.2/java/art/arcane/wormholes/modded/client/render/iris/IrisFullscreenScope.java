package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.wormholes.modded.client.render.PortalDrawScope;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

public final class IrisFullscreenScope implements AutoCloseable {
    private static final IrisFullscreenScope INACTIVE = new IrisFullscreenScope(null);

    private final State state;

    private IrisFullscreenScope(State state) {
        this.state = state;
    }

    public static IrisFullscreenScope open() {
        if (!PortalStencilRenderer.instance().nested() && PortalDrawScope.current() == null) {
            return INACTIVE;
        }
        IrisFullscreenScope scope = new IrisFullscreenScope(new State(IrisClipPlanes.capture(), GL11C.glGetInteger(GL11C.GL_FRONT_FACE),
            GL11C.glIsEnabled(GL11C.GL_CULL_FACE)));
        for (int distance = 0; distance < 8; distance++) {
            GL11C.glDisable(GL30C.GL_CLIP_DISTANCE0 + distance);
        }
        GL11C.glFrontFace(GL11C.GL_CCW);
        GlStateManager._disableCull();
        return scope;
    }

    @Override
    public void close() {
        if (state == null) {
            return;
        }
        try {
            state.clipping().restore();
        } finally {
            GL11C.glFrontFace(state.frontFace());
            if (state.culling()) {
                GlStateManager._enableCull();
            } else {
                GlStateManager._disableCull();
            }
        }
    }

    private record State(IrisClipPlanes.State clipping, int frontFace, boolean culling) {
    }
}
