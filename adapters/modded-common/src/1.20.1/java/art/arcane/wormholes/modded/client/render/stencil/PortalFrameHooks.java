package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.mixin.client.PortalLevelRendererAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;

/**
 * The only entry points the vanilla render mixins call. Keeping mixins thin lets each lane change renderer internals
 * without touching mixin classes. Render thread only.
 */
public final class PortalFrameHooks {
    private static int layerDepth;

    private PortalFrameHooks() {
    }

    /** Receives the projection {@code GameRenderer.renderLevel} hands to {@code LevelRenderer.renderLevel}. */
    public static void captureProjection(Matrix4f projection) {
        ClientPortalRenderer.instance().captureProjection(projection);
        PortalStencilRenderer.instance().captureProjection(projection);
    }

    /**
     * Runs the portal pass inside {@code LevelRenderer.renderLevel}, after opaque and cutout geometry and before the
     * translucent branch. In the main view it first prepares the frame and composites streamed mesh views; inside a
     * layer it draws the next stencil depth.
     */
    public static void beforeTranslucent(LevelRenderer renderer, PoseStack poseStack, Matrix4f projection, Camera camera, float partialTicks) {
        PortalLevelRendererAccess access = (PortalLevelRendererAccess) renderer;
        Frustum frustum = access.wormholes$capturedFrustum() != null ? access.wormholes$capturedFrustum() : access.wormholes$cullingFrustum();
        FrameCamera frame = new FrameCamera(camera, camera.getPosition(), new Matrix4f(poseStack.last().pose()), new Matrix4f(projection), frustum,
            partialTicks);
        PortalStencilRenderer stencil = PortalStencilRenderer.instance();
        if (!inLayer()) {
            stencil.prepareFrame(renderer, frame);
            ClientPortalRenderer streamed = ClientPortalRenderer.instance();
            if (stencil.meshViews()) {
                streamed.prepare(frame);
                streamed.composite(poseStack, projection);
            } else {
                streamed.discardFrame();
            }
        }
        stencil.crossPortalEntities(frame, poseStack);
        if (stencil.renderPortals(frame)) {
            ClientLevel level = access.wormholes$level();
            if (level != null) {
                PortalWorldRenderer.restoreLighting(level, poseStack);
            }
        }
    }

    /** Called at the tail of {@code LevelRenderer.renderLevel} for every renderer, main or layer. */
    public static void afterLevel(LevelRenderer renderer) {
        if (!inLayer()) {
            PortalStencil.restore(0);
            PortalStencil.clipping(false);
            PortalClipState.set(null);
        }
    }

    /** Whether a portal layer is currently re-entering a level renderer. */
    public static boolean inLayer() {
        return layerDepth > 0;
    }

    /** Current layer depth, 0 in the main view. */
    public static int layerDepth() {
        return layerDepth;
    }

    /** Marks entry into a layer; pair with {@link #exitLayer()}. */
    public static void enterLayer() {
        layerDepth++;
    }

    /** Marks exit from a layer. */
    public static void exitLayer() {
        if (layerDepth > 0) {
            layerDepth--;
        }
    }
}
