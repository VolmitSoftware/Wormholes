package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.modded.client.render.stencil.FrameCamera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * A streamed view's camera: the main camera carried through the portal transforms that lead to the view. The 1.20.1
 * stand-in for the 26.x {@code CameraRenderState} the streamed renderer passed around.
 */
record PortalViewCamera(Vec3 pos, BlockPos blockPos, Matrix4f viewRotation, Matrix4f projection, Frustum frustum, float partialTicks) {
    static PortalViewCamera of(FrameCamera camera) {
        return new PortalViewCamera(camera.pos(), BlockPos.containing(camera.pos()), new Matrix4f(camera.viewRotation()),
            new Matrix4f(camera.projection()), camera.frustum(), camera.partialTicks());
    }

    /** This camera seen through {@code toRoot}, the map from the view's space to the root camera's space. */
    PortalViewCamera transformed(Matrix4d toRoot, Matrix4f projection) {
        if (toRoot.equals(new Matrix4d())) {
            return this;
        }
        Vector3d position = new Matrix4d(toRoot).invert().transformPosition(new Vector3d(pos.x, pos.y, pos.z));
        Matrix4f rotation = new Matrix4f(viewRotation).mul(new Matrix4f(toRoot).m30(0).m31(0).m32(0));
        Frustum frustum = new Frustum(rotation, projection);
        frustum.prepare(position.x, position.y, position.z);
        Vec3 eye = new Vec3(position.x, position.y, position.z);
        return new PortalViewCamera(eye, BlockPos.containing(eye), rotation, projection, frustum, partialTicks);
    }
}
