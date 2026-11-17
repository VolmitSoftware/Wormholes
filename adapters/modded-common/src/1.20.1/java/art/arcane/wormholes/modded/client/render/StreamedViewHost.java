package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.wormholes.modded.client.render.stencil.FrameCamera;
import art.arcane.wormholes.modded.client.render.stencil.PortalFrameHooks;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalViewSelection;

/** What the streamed renderer asks of the stencil renderer and the shader pipeline around it. Render thread only. */
final class StreamedViewHost {
    private StreamedViewHost() {
    }

    /** Whether a stencil view is being drawn right now (the streamed renderer is then inside a layer). */
    static boolean nested() {
        return PortalFrameHooks.inLayer();
    }

    /** Whether a stencil view draws this opening this frame, so the streamed path must not. */
    static boolean claims(ApertureDescriptor geometry) {
        return PortalStencilRenderer.instance().claims(geometry);
    }

    /** Whether the stencil renderer draws the child view with this stream key in the current layer. */
    static boolean claimsChild(int streamKey) {
        return PortalStencilRenderer.instance().claimsChild(streamKey);
    }

    /** Whether a shader pack replaces the world pipeline; streamed views then composite after its final pass. */
    static boolean shaderPack() {
        return PortalShaderScope.shaders();
    }

    /** Whether the shader pack is drawing its shadow map. */
    static boolean shadowPass() {
        return PortalShaderScope.shadowPass();
    }

    /** The screen window [-1, 1] the current stencil layer covers, for a root camera inside it. */
    static PortalViewSelection.Window window(FrameCamera camera) {
        return PortalStencilRenderer.instance().selectionCamera(camera).window();
    }

    /** Whether {@code surface} can be seen from a root camera inside the current stencil layer. */
    static boolean visible(PortalSurface surface, FrameCamera camera) {
        return PortalViewSelection.visible(surface, PortalStencilRenderer.instance().selectionCamera(camera));
    }
}
