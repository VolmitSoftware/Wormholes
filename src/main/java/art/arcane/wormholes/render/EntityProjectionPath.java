package art.arcane.wormholes.render;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;

import art.arcane.wormholes.network.view.EntityVisual;

import com.github.retrooper.packetevents.util.Vector3d;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class EntityProjectionPath {
    private final EntityProjectionPath parent;
    private final ProjectorRecursivePortals.Candidate entrance;
    private final Root root;
    private final double[] scratch = new double[3];
    final ProjectorRecursivePortals.Index index;
    final int remainingDepth;

    EntityProjectionPath(Root root, ProjectorRecursivePortals portals) {
        this.parent = null;
        this.entrance = null;
        this.root = root;
        this.remainingDepth = Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH;
        Vector source = root.remote().getOrigin();
        Vector target = root.local().getOrigin();
        Location eye = root.eye();
        if (root.mirror()) {
            PortalCoordMap.mirrorDisplayToSourcePointInto(eye.getX(), eye.getY(), eye.getZ(),
                target.getX(), target.getY(), target.getZ(), root.local().getFrame(), root.quarterTurns(), scratch);
        } else {
            PortalCoordMap.transformPointInto(eye.getX(), eye.getY(), eye.getZ(),
                target.getX(), target.getY(), target.getZ(), source.getX(), source.getY(), source.getZ(),
                root.localFrame(), root.remoteFrame(), scratch);
        }
        this.index = portals.indexFor(root.remote().getWorld(), scratch[0], scratch[1], scratch[2], root.remote());
    }

    private EntityProjectionPath(EntityProjectionPath parent, ProjectorRecursivePortals.Candidate entrance,
                                  ProjectorRecursivePortals portals) {
        this.parent = parent;
        this.entrance = entrance;
        this.root = parent.root;
        this.remainingDepth = parent.remainingDepth - 1;
        this.index = portals.indexFor(entrance.nestedWorld, entrance.transformedEyeX, entrance.transformedEyeY,
            entrance.transformedEyeZ, entrance.nestedDestination);
    }

    EntityProjectionPath child(ProjectorRecursivePortals.Candidate candidate, ProjectorRecursivePortals portals) {
        if (remainingDepth <= 0 || !candidate.traversable) {
            return null;
        }
        for (EntityProjectionPath step = this; step.entrance != null; step = step.parent) {
            if (step.entrance.portalId.equals(candidate.portalId)) {
                return null;
            }
        }
        return intersects(candidate.view) ? new EntityProjectionPath(this, candidate, portals) : null;
    }

    boolean nested() {
        return parent != null;
    }

    boolean visible(double x, double y, double z, BoundingBox bounds, double[] out) {
        if (bounds == null) {
            return visible(x, y, z, out);
        }
        return visibleBounds(x, y, z, bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
            bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ(), out);
    }

    boolean visible(EntityVisual visual, double centerY, double[] out) {
        return visibleBounds(visual.x(), centerY, visual.z(), visual.x() - 0.5D, visual.y(), visual.z() - 0.5D,
            visual.x() + 0.5D, visual.y() + visual.height(), visual.z() + 0.5D, out);
    }

    private boolean visibleBounds(double x, double y, double z, double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ, double[] out) {
        if (visible(x, y, z, out)) {
            return true;
        }
        for (int corner = 0; corner < 8; corner++) {
            if (visible((corner & 1) == 0 ? minX : maxX, (corner & 2) == 0 ? minY : maxY,
                (corner & 4) == 0 ? minZ : maxZ, out)) {
                point(x, y, z, out);
                return true;
            }
        }
        return false;
    }

    boolean visible(double x, double y, double z, double[] out) {
        if (index.find(x, y, z, remainingDepth) != null) {
            return false;
        }
        out[0] = x;
        out[1] = y;
        out[2] = z;
        for (EntityProjectionPath step = this; step.entrance != null; step = step.parent) {
            step.entrance.sourceToDisplayPoint(out[0], out[1], out[2], out);
            ProjectorRecursivePortals.Hit hit = step.parent.index.find(out[0], out[1], out[2], step.parent.remainingDepth);
            if (hit == null || !hit.traversable || !step.entrance.portalId.equals(hit.portalId)) {
                return false;
            }
        }
        rootPoint(out[0], out[1], out[2], out);
        return root.frustum().containsPrimitive(out[0], out[1], out[2]);
    }

    boolean fullyHidden(ProjectedEntityOcclusion occlusion, double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ) {
        sourcePoint(minX, minY, minZ, scratch);
        double x = scratch[0];
        double y = scratch[1];
        double z = scratch[2];
        sourcePoint(maxX, maxY, maxZ, scratch);
        return occlusion.fullyHidden(Math.min(x, scratch[0]), Math.min(y, scratch[1]), Math.min(z, scratch[2]),
            Math.max(x, scratch[0]), Math.max(y, scratch[1]), Math.max(z, scratch[2]));
    }

    private void sourcePoint(double x, double y, double z, double[] out) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
        for (EntityProjectionPath step = this; step.entrance != null; step = step.parent) {
            step.entrance.sourceToDisplayPoint(out[0], out[1], out[2], out);
        }
    }

    void point(double x, double y, double z, double[] out) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
        for (EntityProjectionPath step = this; step.entrance != null; step = step.parent) {
            step.entrance.sourceToDisplayPoint(out[0], out[1], out[2], out);
        }
        rootPoint(out[0], out[1], out[2], out);
    }

    void vector(double x, double y, double z, double[] out) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
        for (EntityProjectionPath step = this; step.entrance != null; step = step.parent) {
            step.entrance.sourceToDisplayVector(out[0], out[1], out[2], out);
        }
        if (root.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(out[0], out[1], out[2], root.local().getFrame(),
                root.quarterTurns(), out);
        } else {
            root.remoteFrame().transformVectorInto(out[0], out[1], out[2], root.localFrame(), out);
        }
    }

    boolean upsideDown() {
        vector(0.0D, 1.0D, 0.0D, scratch);
        return scratch[1] < -0.5D;
    }

    int itemFrameTransform(Direction facing) {
        Direction top = ProjectedItemFrameTransform.canonicalTop(facing);
        Direction right = ProjectedItemFrameTransform.cross(facing, top);
        return ProjectedItemFrameTransform.encode(direction(facing), direction(top), direction(right));
    }

    Vector3d anchor(double x, double y, double z) {
        point(Math.floor(x) + 0.5D, Math.floor(y) + 0.5D, Math.floor(z) + 0.5D, scratch);
        double targetX = scratch[0];
        double targetY = scratch[1];
        double targetZ = scratch[2];
        vector(1.0D, 1.0D, 1.0D, scratch);
        double tolerance = ProjectorFrameTransform.coordinateSnapTolerance(x, y, z, targetX, targetY, targetZ);
        return ProjectedItemFrameTransform.anchorPosition(targetX, targetY, targetZ,
            scratch[0], scratch[1], scratch[2], tolerance);
    }

    private Direction direction(Direction source) {
        vector(source.x(), source.y(), source.z(), scratch);
        return Direction.closest(scratch[0], scratch[1], scratch[2]);
    }

    private void rootPoint(double x, double y, double z, double[] out) {
        Vector source = root.remote().getOrigin();
        Vector target = root.local().getOrigin();
        if (root.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayPointInto(x, y, z, target.getX(), target.getY(), target.getZ(),
                root.local().getFrame(), root.quarterTurns(), out);
        } else {
            PortalCoordMap.transformPointInto(x, y, z, source.getX(), source.getY(), source.getZ(),
                target.getX(), target.getY(), target.getZ(), root.remoteFrame(), root.localFrame(), out);
        }
    }

    private boolean intersects(AxisAlignedBB view) {
        AxisAlignedBB region = root.frustum().getRegion();
        point(view.getXa(), view.getYa(), view.getZa(), scratch);
        double x = scratch[0];
        double y = scratch[1];
        double z = scratch[2];
        point(view.getXb(), view.getYb(), view.getZb(), scratch);
        return Math.min(x, scratch[0]) <= region.getXb() && Math.max(x, scratch[0]) >= region.getXa()
            && Math.min(y, scratch[1]) <= region.getYb() && Math.max(y, scratch[1]) >= region.getYa()
            && Math.min(z, scratch[2]) <= region.getZb() && Math.max(z, scratch[2]) >= region.getZa();
    }

    record Root(ILocalPortal local, ILocalPortal remote, PortalFrame localFrame, PortalFrame remoteFrame,
                boolean mirror, int quarterTurns, Location eye, Frustum4D frustum) {
    }
}
