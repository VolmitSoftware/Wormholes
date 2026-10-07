package art.arcane.optics.recursion;

import java.util.ArrayList;
import java.util.List;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.volume.PlaneWindow;
import art.arcane.optics.volume.ProjectionVolume;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.aperture.ApertureDescriptor;

public final class ClientRecursionPlanner {
    private final int depthCap;

    public ClientRecursionPlanner(int depthCap) {
        this.depthCap = Math.max(0, depthCap);
    }

    public List<NestedCone> plan(ApertureDescriptor root, double eyeX, double eyeY, double eyeZ) {
        int limit = Math.min(depthCap, root.recursionDepth());
        if (limit <= 0 || root.nested().isEmpty() || !root.valid()) {
            return List.of();
        }
        List<NestedCone> cones = new ArrayList<NestedCone>(root.nested().size());
        List<Window> chain = List.of(new Window(root, OpticTransform.IDENTITY, List.of(), eyeX, eyeY, eyeZ));
        descend(root, OpticTransform.IDENTITY, List.of(), chain, 1, limit, eyeX, eyeY, eyeZ, cones);
        return cones;
    }

    public static boolean destinationReaches(ApertureDescriptor parent, OpticTransform transform, Box destinationArea) {
        if (destinationArea == null || transform == null) {
            return false;
        }
        Vec3d center = transform.point(destinationArea.center());
        double[] extent = new double[3];
        transform.vectorInto((destinationArea.getXb() - destinationArea.getXa()) * 0.5D, (destinationArea.getYb() - destinationArea.getYa()) * 0.5D,
            (destinationArea.getZb() - destinationArea.getZa()) * 0.5D, extent);
        double dx = Math.abs(extent[0]);
        double dy = Math.abs(extent[1]);
        double dz = Math.abs(extent[2]);
        Box area = new Box(center.x() - dx, center.x() + dx, center.y() - dy, center.y() + dy, center.z() - dz, center.z() + dz);
        Box aperture = parent.apertureArea();
        ProjectionVolume volume = ProjectionVolume.of(aperture, parent.frame(), parent.planeCoordinate(), parent.frontSide(),
            parent.depthBlocks(), 0.0D);
        int normalAxis = volume.normalAxis();
        if (!volume.reaches(volume.distance(low(area, normalAxis)), volume.distance(high(area, normalAxis)))) {
            return false;
        }
        double distance = volume.maxDepth();
        for (int axis = 0; axis < 3; axis++) {
            if (axis != normalAxis && (high(area, axis) < low(aperture, axis) - distance
                || low(area, axis) > high(aperture, axis) + distance)) {
                return false;
            }
        }
        return true;
    }

    private static void descend(ApertureDescriptor parent, OpticTransform parentTransform, List<ApertureDescriptor> parentReflections,
                                List<Window> chain, int depth, int limit, double eyeX, double eyeY, double eyeZ, List<NestedCone> out) {
        OpticTransform transform = parent.mirror() ? parentTransform.compose(parent.mirrorTransform()) : parentTransform;
        List<ApertureDescriptor> reflections = parent.mirror() ? reflections(parent, parentReflections) : parentReflections;
        double[] scratch = new double[3];
        for (ApertureDescriptor child : parent.nested()) {
            if (!child.valid()) {
                continue;
            }
            Window window = new Window(child, transform, reflections, eyeX, eyeY, eyeZ);
            if (!window.servesEye() || !anyCornerVisible(chain, window, scratch)) {
                continue;
            }
            out.add(new NestedCone(child, depth, chain, window));
            int childLimit = Math.min(limit, depth + child.recursionDepth());
            if (depth < childLimit && !child.nested().isEmpty()) {
                List<Window> childChain = new ArrayList<Window>(chain.size() + 1);
                childChain.addAll(chain);
                childChain.add(window);
                descend(child, transform, reflections, List.copyOf(childChain), depth + 1, childLimit, eyeX, eyeY, eyeZ, out);
            }
        }
    }

    private static List<ApertureDescriptor> reflections(ApertureDescriptor mirror, List<ApertureDescriptor> parentReflections) {
        List<ApertureDescriptor> chain = new ArrayList<ApertureDescriptor>(parentReflections.size() + 1);
        chain.add(mirror);
        chain.addAll(parentReflections);
        return List.copyOf(chain);
    }

    private static boolean anyCornerVisible(List<Window> chain, Window window, double[] scratch) {
        Box area = window.area;
        Vec3d center = area.center();
        window.transform.pointInto(center.getX(), center.getY(), center.getZ(), scratch);
        if (visible(chain, scratch[0], scratch[1], scratch[2])) {
            return true;
        }
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? area.getXa() : area.getXb();
            double y = (corner & 2) == 0 ? area.getYa() : area.getYb();
            double z = (corner & 4) == 0 ? area.getZa() : area.getZb();
            window.transform.pointInto(x, y, z, scratch);
            if (visible(chain, scratch[0], scratch[1], scratch[2])) {
                return true;
            }
        }
        return false;
    }

    private static double low(Box box, int axis) {
        return axis == 0 ? box.getXa() : axis == 1 ? box.getYa() : box.getZa();
    }

    private static double high(Box box, int axis) {
        return axis == 0 ? box.getXb() : axis == 1 ? box.getYb() : box.getZb();
    }

    private static boolean visible(List<Window> chain, double x, double y, double z) {
        for (int i = 0; i < chain.size(); i++) {
            if (!chain.get(i).passes(x, y, z)) {
                return false;
            }
        }
        return true;
    }

    public static final class NestedCone {
        private final ApertureDescriptor geometry;
        private final int depth;
        private final List<Window> ancestors;
        private final Window window;

        private NestedCone(ApertureDescriptor geometry, int depth, List<Window> ancestors, Window window) {
            this.geometry = geometry;
            this.depth = depth;
            this.ancestors = ancestors;
            this.window = window;
        }

        public ApertureDescriptor geometry() {
            return geometry;
        }

        public int depth() {
            return depth;
        }

        public OpticTransform transform() {
            return window.transform;
        }

        public List<ApertureDescriptor> reflections() {
            return window.reflections;
        }

        public double contentEyeX() {
            return window.eyeX;
        }

        public double contentEyeY() {
            return window.eyeY;
        }

        public double contentEyeZ() {
            return window.eyeZ;
        }

        public List<ApertureDescriptor> ancestors() {
            List<ApertureDescriptor> geometries = new ArrayList<ApertureDescriptor>(ancestors.size());
            for (Window ancestor : ancestors) {
                geometries.add(ancestor.geometry);
            }
            return geometries;
        }

        public boolean visible(double displayX, double displayY, double displayZ) {
            return ClientRecursionPlanner.visible(ancestors, displayX, displayY, displayZ);
        }
    }

    private static final class Window {
        private final ApertureDescriptor geometry;
        private final OpticTransform transform;
        private final OpticTransform content;
        private final List<ApertureDescriptor> reflections;
        private final Box area;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final double eyeX;
        private final double eyeY;
        private final double eyeZ;
        private final boolean eyeFrontSide;
        private final Face normal;
        private final PlaneWindow plane;
        private final double[] scratch;

        private Window(ApertureDescriptor geometry, OpticTransform transform, List<ApertureDescriptor> reflections, double displayEyeX,
                       double displayEyeY, double displayEyeZ) {
            this.geometry = geometry;
            this.transform = transform;
            this.content = transform.inverse();
            this.reflections = reflections;
            ApertureCells aperture = geometry.aperture();
            this.area = aperture.getArea();
            Frame frame = geometry.frame();
            Vec3d center = area.center();
            this.originX = frame.getNormal().x() != 0 ? geometry.planeCoordinate() : center.getX();
            this.originY = frame.getNormal().y() != 0 ? geometry.planeCoordinate() : center.getY();
            this.originZ = frame.getNormal().z() != 0 ? geometry.planeCoordinate() : center.getZ();
            this.scratch = new double[3];
            content.pointInto(displayEyeX, displayEyeY, displayEyeZ, scratch);
            this.eyeX = scratch[0];
            this.eyeY = scratch[1];
            this.eyeZ = scratch[2];
            Face facing = frame.getNormal();
            this.eyeFrontSide = signed(eyeX, eyeY, eyeZ, facing) >= 0.0D;
            Frame projectionFrame = frame.view(eyeFrontSide);
            this.normal = projectionFrame.getNormal();
            this.plane = PlaneWindow.create(aperture, area, projectionFrame, originX, originY, originZ,
                geometry.aperturePadding(), signed(eyeX, eyeY, eyeZ, normal));
        }

        private boolean servesEye() {
            return eyeFrontSide == geometry.frontSide();
        }

        private boolean passes(double displayX, double displayY, double displayZ) {
            content.pointInto(displayX, displayY, displayZ, scratch);
            double pointDot = signed(scratch[0], scratch[1], scratch[2], normal);
            return plane.containsRayIntersection(eyeX, eyeY, eyeZ, scratch[0], scratch[1], scratch[2], pointDot);
        }

        private double signed(double x, double y, double z, Face direction) {
            return ((x - originX) * direction.x()) + ((y - originY) * direction.y()) + ((z - originZ) * direction.z());
        }
    }
}
