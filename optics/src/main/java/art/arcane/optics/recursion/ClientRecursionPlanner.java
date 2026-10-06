package art.arcane.optics.recursion;

import java.util.ArrayList;
import java.util.List;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.frame.ProjectorFrameTransform;
import art.arcane.optics.volume.PlaneWindow;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.client.ClientSpace;

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
        List<Window> chain = List.of(new Window(root, ClientSpace.IDENTITY, eyeX, eyeY, eyeZ));
        descend(root, ClientSpace.IDENTITY, chain, 1, limit, eyeX, eyeY, eyeZ, cones);
        return cones;
    }

    public int depthCap() {
        return depthCap;
    }

    public static boolean destinationReaches(ApertureDescriptor parent, ProjectionEnvironment.Transform transform,
                                             Box destinationArea) {
        if (destinationArea == null || transform == null) {
            return false;
        }
        Vec3d center = destinationArea.center();
        double ex = (destinationArea.getXb() - destinationArea.getXa()) * 0.5D;
        double ey = (destinationArea.getYb() - destinationArea.getYa()) * 0.5D;
        double ez = (destinationArea.getZb() - destinationArea.getZa()) * 0.5D;
        Face x = transform.xAxis();
        Face y = transform.yAxis();
        Face z = transform.zAxis();
        double cx = center.x() * x.x() + center.y() * y.x() + center.z() * z.x() + transform.translation().x();
        double cy = center.x() * x.y() + center.y() * y.y() + center.z() * z.y() + transform.translation().y();
        double cz = center.x() * x.z() + center.y() * y.z() + center.z() * z.z() + transform.translation().z();
        double dx = ex * Math.abs(x.x()) + ey * Math.abs(y.x()) + ez * Math.abs(z.x());
        double dy = ex * Math.abs(x.y()) + ey * Math.abs(y.y()) + ez * Math.abs(z.y());
        double dz = ex * Math.abs(x.z()) + ey * Math.abs(y.z()) + ez * Math.abs(z.z());
        Box area = new Box(cx - dx, cx + dx, cy - dy, cy + dy, cz - dz, cz + dz);
        Box aperture = parent.apertureArea();
        Face normal = parent.frame().getNormal();
        int normalAxis = ApertureDescriptor.axisOf(normal);
        double facing = normalAxis == 0 ? normal.x() : normalAxis == 1 ? normal.y() : normal.z();
        double origin = parent.planeCoordinate();
        double clearance = ProjectorFrameTransform.portalPlaneClearance(aperture, parent.frame());
        double distance = parent.depthBlocks() + clearance;
        double signedA = (low(area, normalAxis) - origin) * facing;
        double signedB = (high(area, normalAxis) - origin) * facing;
        double near = parent.frontSide() ? -distance : clearance;
        double far = parent.frontSide() ? -clearance : distance;
        if (Math.max(signedA, signedB) < near || Math.min(signedA, signedB) > far) {
            return false;
        }
        for (int axis = 0; axis < 3; axis++) {
            if (axis != normalAxis && (high(area, axis) < low(aperture, axis) - distance
                || low(area, axis) > high(aperture, axis) + distance)) {
                return false;
            }
        }
        return true;
    }

    public static ClientSpace childSpace(ApertureDescriptor parent, ClientSpace parentSpace) {
        return parent.mirror() ? parentSpace.throughMirror(parent) : parentSpace;
    }

    private static void descend(ApertureDescriptor parent, ClientSpace parentSpace, List<Window> chain, int depth, int limit,
                                double eyeX, double eyeY, double eyeZ, List<NestedCone> out) {
        ClientSpace space = childSpace(parent, parentSpace);
        double[] scratch = new double[3];
        for (ApertureDescriptor child : parent.nested()) {
            if (!child.valid()) {
                continue;
            }
            Window window = new Window(child, space, eyeX, eyeY, eyeZ);
            if (!window.servesEye() || !anyCornerVisible(chain, window, scratch)) {
                continue;
            }
            out.add(new NestedCone(child, depth, chain, window));
            int childLimit = Math.min(limit, depth + child.recursionDepth());
            if (depth < childLimit && !child.nested().isEmpty()) {
                List<Window> childChain = new ArrayList<Window>(chain.size() + 1);
                childChain.addAll(chain);
                childChain.add(window);
                descend(child, space, List.copyOf(childChain), depth + 1, childLimit, eyeX, eyeY, eyeZ, out);
            }
        }
    }

    private static boolean anyCornerVisible(List<Window> chain, Window window, double[] scratch) {
        Box area = window.area;
        Vec3d center = area.center();
        window.space.toDisplay(center.getX(), center.getY(), center.getZ(), scratch);
        if (visible(chain, scratch[0], scratch[1], scratch[2])) {
            return true;
        }
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? area.getXa() : area.getXb();
            double y = (corner & 2) == 0 ? area.getYa() : area.getYb();
            double z = (corner & 4) == 0 ? area.getZa() : area.getZb();
            window.space.toDisplay(x, y, z, scratch);
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

        public ClientSpace space() {
            return window.space;
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
        private final ClientSpace space;
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

        private Window(ApertureDescriptor geometry, ClientSpace space, double displayEyeX, double displayEyeY, double displayEyeZ) {
            this.geometry = geometry;
            this.space = space;
            ApertureCells aperture = geometry.aperture();
            this.area = aperture.getArea();
            Frame frame = geometry.frame();
            Vec3d center = area.center();
            this.originX = frame.getNormal().x() != 0 ? geometry.planeCoordinate() : center.getX();
            this.originY = frame.getNormal().y() != 0 ? geometry.planeCoordinate() : center.getY();
            this.originZ = frame.getNormal().z() != 0 ? geometry.planeCoordinate() : center.getZ();
            this.scratch = new double[3];
            space.toContent(displayEyeX, displayEyeY, displayEyeZ, scratch);
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
            space.toContent(displayX, displayY, displayZ, scratch);
            double pointDot = signed(scratch[0], scratch[1], scratch[2], normal);
            return plane.containsRayIntersection(eyeX, eyeY, eyeZ, scratch[0], scratch[1], scratch[2], pointDot);
        }

        private double signed(double x, double y, double z, Face direction) {
            return ((x - originX) * direction.x()) + ((y - originY) * direction.y()) + ((z - originZ) * direction.z());
        }
    }
}
