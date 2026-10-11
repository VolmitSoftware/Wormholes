package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.PortalShaderScope;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import art.arcane.wormholes.modded.client.render.iris.IrisFullscreenScope;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.lwjgl.opengl.GL11C;

public final class NativeIrisFullscreenAssertions {
    private static boolean verified;

    private NativeIrisFullscreenAssertions() {
    }

    public static void verify() {
        if (verified || !PortalStencilRenderer.instance().nested() && !PortalShaderScope.isRendering()) {
            return;
        }
        verified = true;
        State original = State.capture();
        try {
            expect(new State(new IrisClipPlanes.State(0, original.clipping().enabledDistance()), GL11C.GL_CCW, false));
            State parent = new State(new IrisClipPlanes.State(0x93, -1), GL11C.GL_CW, true);
            parent.restore();
            try (IrisFullscreenScope outer = IrisFullscreenScope.open()) {
                expect(new State(new IrisClipPlanes.State(0, -1), GL11C.GL_CCW, false));
                State nested = new State(new IrisClipPlanes.State(0xA6, -1), GL11C.GL_CW, true);
                nested.restore();
                IllegalStateException expected = new IllegalStateException("iris-fullscreen-scope-exit");
                try {
                    try (IrisFullscreenScope inner = IrisFullscreenScope.open()) {
                        expect(new State(new IrisClipPlanes.State(0, -1), GL11C.GL_CCW, false));
                        throw expected;
                    }
                } catch (IllegalStateException failure) {
                    if (failure != expected) {
                        throw failure;
                    }
                }
                expect(nested);
            }
            expect(parent);
            System.out.println("WORMHOLES_IRIS_FULLSCREEN_SCOPE_PASS all-eight-bits=true winding=true culling=true nested=true exceptional=true");
        } finally {
            original.restore();
        }
    }

    private static void expect(State expected) {
        State actual = State.capture();
        if (!actual.equals(expected)) {
            throw new AssertionError("Iris fullscreen GL state differs: actual=" + actual + " expected=" + expected);
        }
    }

    private record State(IrisClipPlanes.State clipping, int frontFace, boolean culling) {
        private static State capture() {
            return new State(IrisClipPlanes.capture(), GL11C.glGetInteger(GL11C.GL_FRONT_FACE), GL11C.glIsEnabled(GL11C.GL_CULL_FACE));
        }

        private void restore() {
            clipping.restore();
            GL11C.glFrontFace(frontFace);
            if (culling) {
                GlStateManager._enableCull();
            } else {
                GlStateManager._disableCull();
            }
        }
    }
}
