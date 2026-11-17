package art.arcane.wormholes.modded.client.render.stencil;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * One view's camera for one frame; the 1.20.1 stand-in for 26.x {@code CameraRenderState}.
 *
 * @param camera       the vanilla camera (the main camera, or a {@code PortalCamera} inside a layer)
 * @param pos          eye position in world space
 * @param viewRotation rotation-only view matrix (the PoseStack pose handed to {@code LevelRenderer.renderLevel})
 * @param projection   projection matrix for this view
 * @param frustum      culling frustum built from {@code viewRotation} and {@code projection}
 * @param partialTicks frame partial tick
 */
public record FrameCamera(Camera camera, Vec3 pos, Matrix4f viewRotation, Matrix4f projection, Frustum frustum, float partialTicks) {
}
