package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.render.client.ClientPortalAperture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

record PortalViewport(int x, int y, int width, int height) {
    private static final int TILE = 64;

    static PortalViewport coverage(ClientPortalAperture aperture, Matrix4dc transform, int width, int height, boolean zeroToOne) {
        double[] matrix = transform.get(new double[16]);
        double minX = width;
        double minY = height;
        double maxX = 0;
        double maxY = 0;
        ClientPortalAperture.ClipDepth depth = zeroToOne ? ClientPortalAperture.ClipDepth.ZERO_TO_ONE
            : ClientPortalAperture.ClipDepth.NEGATIVE_ONE_TO_ONE;
        for (ClientPortalAperture.Rectangle rectangle : aperture.rectangles()) {
            for (ClientPortalAperture.ClipVertex vertex : aperture.project(rectangle, matrix, depth)) {
                double screenX = (vertex.x() / vertex.w() + 1) * width * 0.5;
                double screenY = (vertex.y() / vertex.w() + 1) * height * 0.5;
                minX = Math.min(minX, screenX);
                minY = Math.min(minY, screenY);
                maxX = Math.max(maxX, screenX);
                maxY = Math.max(maxY, screenY);
            }
        }
        if (minX >= maxX || minY >= maxY) {
            return null;
        }
        int left = Math.max(0, (int) Math.floor(minX / TILE) * TILE);
        int bottom = Math.max(0, (int) Math.floor(minY / TILE) * TILE);
        int right = Math.min(width, (int) Math.ceil(maxX / TILE) * TILE);
        int top = Math.min(height, (int) Math.ceil(maxY / TILE) * TILE);
        return right > left && top > bottom ? new PortalViewport(left, bottom, right - left, top - bottom) : null;
    }

    PortalViewport intersect(PortalViewport other) {
        int left = Math.max(x, other.x);
        int bottom = Math.max(y, other.y);
        int right = Math.min(x + width, other.x + other.width);
        int top = Math.min(y + height, other.y + other.height);
        return right > left && top > bottom ? new PortalViewport(left, bottom, right - left, top - bottom) : null;
    }

    Frustum frustum(CameraRenderState camera, Matrix4fc projection, int targetWidth, int targetHeight) {
        float left = Math.max(0, x - 1);
        float bottom = Math.max(0, y - 1);
        float right = Math.min(targetWidth, x + width + 1);
        float top = Math.min(targetHeight, y + height + 1);
        Matrix4f croppedProjection = new Matrix4f()
            .m00(targetWidth / (right - left)).m11(targetHeight / (top - bottom))
            .m30((targetWidth - right - left) / (right - left))
            .m31((targetHeight - top - bottom) / (top - bottom))
            .mul(projection);
        Frustum frustum = new Frustum(camera.viewRotationMatrix, croppedProjection);
        frustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
        return frustum;
    }

}
