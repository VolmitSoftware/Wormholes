package art.arcane.wormholes.modded.client.render.dh;

import art.arcane.wormholes.modded.client.render.stencil.PortalFrameHooks;
import org.lwjgl.opengl.GL11C;

/**
 * Gates the four Distant Horizons entry points ({@code ClientApi.renderLods}, {@code renderDeferredLodsForShaders},
 * {@code renderFadeOpaque}, {@code renderFadeTransparent}). Inside a portal layer every pass is cancelled. In the main
 * view, {@code renderLods} runs before the portal pass and starts the frame; once a layer has drawn this frame, the
 * stencil holds the aperture marks, so the later main-view passes are stencil-tested to draw only where it is zero.
 * DH saves and restores the stencil state around its own work without changing it, so the test reaches its copy into
 * the main target. Render thread only.
 */
public final class DhLodPasses {
    private static final int ALL_BITS = 0xFF;

    private static boolean layerDrawn;
    private static boolean masking;
    private static boolean stencilTest;
    private static int stencilFunc;
    private static int stencilRef;
    private static int stencilValueMask;
    private static int stencilWriteMask;
    private static int stencilFail;
    private static int stencilDepthFail;
    private static int stencilPass;

    private DhLodPasses() {
    }

    /** Before {@code renderLods}; returns whether to cancel it. */
    public static boolean beginLods() {
        if (PortalFrameHooks.inLayer()) {
            layerDrawn = true;
            return true;
        }
        layerDrawn = false;
        return false;
    }

    /** Before any later pass; returns whether to cancel it. */
    public static boolean beginPass() {
        if (PortalFrameHooks.inLayer()) {
            layerDrawn = true;
            return true;
        }
        if (layerDrawn && !masking) {
            mask();
        }
        return false;
    }

    /** After any pass that ran. */
    public static void endPass() {
        if (masking) {
            unmask();
        }
    }

    private static void mask() {
        stencilTest = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        stencilFunc = GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC);
        stencilRef = GL11C.glGetInteger(GL11C.GL_STENCIL_REF);
        stencilValueMask = GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK);
        stencilWriteMask = GL11C.glGetInteger(GL11C.GL_STENCIL_WRITEMASK);
        stencilFail = GL11C.glGetInteger(GL11C.GL_STENCIL_FAIL);
        stencilDepthFail = GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_FAIL);
        stencilPass = GL11C.glGetInteger(GL11C.GL_STENCIL_PASS_DEPTH_PASS);
        GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        GL11C.glStencilFunc(GL11C.GL_EQUAL, 0, ALL_BITS);
        GL11C.glStencilOp(GL11C.GL_KEEP, GL11C.GL_KEEP, GL11C.GL_KEEP);
        GL11C.glStencilMask(0);
        masking = true;
    }

    private static void unmask() {
        masking = false;
        GL11C.glStencilFunc(stencilFunc, stencilRef, stencilValueMask);
        GL11C.glStencilOp(stencilFail, stencilDepthFail, stencilPass);
        GL11C.glStencilMask(stencilWriteMask);
        if (stencilTest) {
            GL11C.glEnable(GL11C.GL_STENCIL_TEST);
        } else {
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        }
    }
}
