package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.AperturePolygon;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class PortalViewSelection {
    private static final double NEAR_CULL_BLOCKS = 0.5D;
    private static final double CLIP_MARGIN = 1.0E-5D;

    private PortalViewSelection() {
    }

    public static Camera through(Camera camera, PortalView view) {
        Vec3d eye = PortalLayerMath.innerCamera(view.toDestination(), camera.eye());
        Matrix4f rotation = camera.view() == null ? null : PortalLayerMath.innerView(camera.view(), view.toDestination());
        Frustum frustum = rotation == null || camera.projection() == null ? null
            : new Frustum(rotation, PortalLayerMath.cullingProjection(camera.projection(), camera.zeroToOne()));
        if (frustum != null) {
            frustum.prepare(eye.x(), eye.y(), eye.z());
        }
        return new Camera(eye, rotation, camera.projection(), frustum, coverage(view.surface(), camera), view.exit(), camera.zeroToOne());
    }

    public static List<PortalView> collect(List<PortalView> children, List<PortalView> roots, Options options, Camera camera) {
        List<PortalView> selected = new ArrayList<>();
        collect(children, options, camera, selected);
        collect(roots, options, camera, selected);
        selected.sort(Comparator.comparingDouble(view -> view.surface().distance(camera.eye())));
        return selected;
    }

    public static boolean visible(PortalSurface surface, Camera camera) {
        Box area = surface.area();
        if (camera.exit() != null) {
            double farthest = Double.NEGATIVE_INFINITY;
            for (AperturePolygon.Rectangle rectangle : covered(surface.aperture())) {
                for (Vec3d vertex : surface.aperture().vertices(rectangle)) {
                    farthest = Math.max(farthest, camera.exit().signedDistance(surface.placement().point(vertex)));
                }
            }
            if (farthest <= CLIP_MARGIN) {
                return false;
            }
        }
        if (camera.frustum() != null && surface.distance(camera.eye()) > NEAR_CULL_BLOCKS
            && !camera.frustum().isVisible(new AABB(area.getXa(), area.getYa(), area.getZa(),
                area.getXb(), area.getYb(), area.getZb()).inflate(0.01D))) {
            return false;
        }
        return coverage(surface, camera) != null;
    }

    public static Window coverage(PortalSurface surface, Camera camera) {
        if (camera.view() == null || camera.projection() == null) {
            return camera.window();
        }
        Matrix4d transform = new Matrix4d(camera.projection()).mul(new Matrix4d(camera.view()))
            .translate(-camera.eye().x(), -camera.eye().y(), -camera.eye().z()).mul(surface.model()
                .translate(-surface.geometry().originX(), -surface.geometry().originY(), -surface.geometry().originZ()));
        double[] matrix = transform.get(new double[16]);
        double minX = 1.0D;
        double minY = 1.0D;
        double maxX = -1.0D;
        double maxY = -1.0D;
        AperturePolygon aperture = surface.aperture();
        AperturePolygon.ClipDepth depth = camera.zeroToOne() ? AperturePolygon.ClipDepth.ZERO_TO_ONE
            : AperturePolygon.ClipDepth.NEGATIVE_ONE_TO_ONE;
        for (AperturePolygon.Rectangle rectangle : covered(aperture)) {
            for (AperturePolygon.ClipVertex vertex : aperture.project(rectangle, matrix, depth)) {
                double x = vertex.x() / vertex.w();
                double y = vertex.y() / vertex.w();
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (minX >= maxX || minY >= maxY) {
            return surface.distance(camera.eye()) <= NEAR_CULL_BLOCKS ? camera.window() : null;
        }
        return new Window(minX, minY, maxX, maxY).intersect(camera.window());
    }

    private static List<AperturePolygon.Rectangle> covered(AperturePolygon aperture) {
        return aperture.hasShape() ? List.of(new AperturePolygon.Rectangle(0, 0,
            aperture.planeShape().columns(), aperture.planeShape().rows())) : aperture.rectangles();
    }

    private static void collect(List<PortalView> views, Options options, Camera camera, List<PortalView> selected) {
        for (PortalView view : views) {
            if (view.source() != options.level() || options.depth() >= Math.min(options.ceiling(), view.recursion())
                || !options.available().test(view) || !view.surface().servesEye(camera.eye())
                || view.surface().distance(camera.eye()) > options.range() || !visible(view.surface(), camera)) {
                continue;
            }
            boolean duplicate = false;
            for (PortalView candidate : selected) {
                if (candidate.surface().sameOpening(view.surface())) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                selected.add(view);
            }
        }
    }

    public record Options(ClientLevel level, int depth, int ceiling, double range, Predicate<PortalView> available) {
    }

    public record Camera(Vec3d eye, Matrix4fc view, Matrix4fc projection, Frustum frustum, Window window,
                         PortalSurface exit, boolean zeroToOne) {
    }

    public record Window(double minX, double minY, double maxX, double maxY) {
        public static final Window FULL = new Window(-1.0D, -1.0D, 1.0D, 1.0D);

        public Window intersect(Window other) {
            double left = Math.max(minX, other.minX);
            double bottom = Math.max(minY, other.minY);
            double right = Math.min(maxX, other.maxX);
            double top = Math.min(maxY, other.maxY);
            return left < right && bottom < top ? new Window(left, bottom, right, top) : null;
        }
    }
}
