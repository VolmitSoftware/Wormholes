package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.shape.PlaneShape;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;

record PortalViewport(int x, int y, int width, int height) {
    private static final int TILE = 64;

    static PortalViewport coverage(AperturePolygon aperture, Matrix4dc transform, int width, int height, boolean zeroToOne) {
        double[] matrix = transform.get(new double[16]);
        double minX = width;
        double minY = height;
        double maxX = 0;
        double maxY = 0;
        AperturePolygon.ClipDepth depth = zeroToOne ? AperturePolygon.ClipDepth.ZERO_TO_ONE
            : AperturePolygon.ClipDepth.NEGATIVE_ONE_TO_ONE;
        for (AperturePolygon.Rectangle rectangle : covered(aperture)) {
            for (AperturePolygon.ClipVertex vertex : aperture.project(rectangle, matrix, depth)) {
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

    PortalViewport rescale(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            throw new IllegalArgumentException("viewport dimensions");
        }
        long left = Math.clamp((long) x, 0L, sourceWidth);
        long bottom = Math.clamp((long) y, 0L, sourceHeight);
        long right = Math.clamp((long) x + width, 0L, sourceWidth);
        long top = Math.clamp((long) y + height, 0L, sourceHeight);
        if (right <= left || top <= bottom) {
            return null;
        }
        if (sourceWidth == targetWidth && sourceHeight == targetHeight
            && left == x && bottom == y && right - left == width && top - bottom == height) {
            return this;
        }
        int targetLeft = (int) (left * targetWidth / sourceWidth);
        int targetBottom = (int) (bottom * targetHeight / sourceHeight);
        int targetRight = (int) ((right * targetWidth + sourceWidth - 1L) / sourceWidth);
        int targetTop = (int) ((top * targetHeight + sourceHeight - 1L) / sourceHeight);
        return new PortalViewport(targetLeft, targetBottom, targetRight - targetLeft, targetTop - targetBottom);
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

    private static List<AperturePolygon.Rectangle> covered(AperturePolygon aperture) {
        if (!aperture.hasShape()) {
            return aperture.rectangles();
        }
        PlaneShape plane = aperture.planeShape();
        return List.of(new AperturePolygon.Rectangle(0, 0, plane.columns(), plane.rows()));
    }
}
