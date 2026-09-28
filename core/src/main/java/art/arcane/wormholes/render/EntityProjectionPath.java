package art.arcane.wormholes.render;


import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.render.view.ProjectionBlockView;

import art.arcane.wormholes.network.view.EntityVisual;


import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class EntityProjectionPath<W, P extends IPortal> {
    private final EntityProjectionPath<W, P> parent;
    private final ProjectorRecursivePortals<W, P>.Candidate entrance;
    private final Root<W, P> root;
    private final double[] scratch = new double[3];
    public final ProjectorRecursivePortals<W, P>.Index index;
    public final int remainingDepth;

    public EntityProjectionPath(Root<W, P> root, ProjectorRecursivePortals<W, P> portals) {
        this.parent = null;
        this.entrance = null;
        this.root = root;
        this.remainingDepth = root.recursiveDepth();
        GeometryVector source = root.remote().getOrigin();
        GeometryVector target = root.local().getOrigin();
        GeometryVector eye = root.eye();
        if (root.mirror()) {
            PortalCoordMap.mirrorDisplayToSourcePointInto(eye.getX(), eye.getY(), eye.getZ(),
                target.getX(), target.getY(), target.getZ(), root.local().getFrame(), root.quarterTurns(), scratch);
        } else {
            PortalCoordMap.transformPointInto(eye.getX(), eye.getY(), eye.getZ(),
                target.getX(), target.getY(), target.getZ(), source.getX(), source.getY(), source.getZ(),
                root.localFrame(), root.remoteFrame(), scratch);
        }
        this.index = portals.indexFor(root.world(), scratch[0], scratch[1], scratch[2], root.remote());
    }

    private EntityProjectionPath(EntityProjectionPath<W, P> parent, ProjectorRecursivePortals<W, P>.Candidate entrance,
                                  ProjectorRecursivePortals<W, P> portals) {
        this.parent = parent;
        this.entrance = entrance;
        this.root = parent.root;
        this.remainingDepth = parent.remainingDepth - 1;
        this.index = portals.indexFor(entrance.nestedWorld, entrance.transformedEyeX, entrance.transformedEyeY,
            entrance.transformedEyeZ, entrance.nestedDestination);
    }

    public EntityProjectionPath<W, P> child(ProjectorRecursivePortals<W, P>.Candidate candidate, ProjectorRecursivePortals<W, P> portals) {
        if (remainingDepth <= 0 || !candidate.traversable) {
            return null;
        }
        for (EntityProjectionPath<W, P> step = this; step.entrance != null; step = step.parent) {
            if (step.entrance.portalId.equals(candidate.portalId)) {
                return null;
            }
        }
        return intersects(candidate.view) ? new EntityProjectionPath<>(this, candidate, portals) : null;
    }

    public boolean nested() {
        return parent != null;
    }

    public boolean visible(EntityVisual visual, double centerY, double[] out) {
        return visibleBounds(visual.x(), centerY, visual.z(), visual.x() - 0.5D, visual.y(), visual.z() - 0.5D,
            visual.x() + 0.5D, visual.y() + visual.height(), visual.z() + 0.5D, out);
    }

    public boolean visibleBounds(double x, double y, double z, double minX, double minY, double minZ,
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

    public boolean visible(double x, double y, double z, double[] out) {
        if (index.find(x, y, z, remainingDepth) != null) {
            return false;
        }
        out[0] = x;
        out[1] = y;
        out[2] = z;
        for (EntityProjectionPath<W, P> step = this; step.entrance != null; step = step.parent) {
            step.entrance.sourceToDisplayPoint(out[0], out[1], out[2], out);
            ProjectorRecursivePortals.Hit<W, P> hit = step.parent.index.find(out[0], out[1], out[2], step.parent.remainingDepth);
            if (hit == null || !hit.traversable || !step.entrance.portalId.equals(hit.portalId)) {
                return false;
            }
        }
        rootPoint(out[0], out[1], out[2], out);
        return root.frustum().containsPrimitive(out[0], out[1], out[2]);
    }

    public <B, V extends ProjectionBlockView<B>> boolean fullyHidden(ProjectedEntityOcclusion<B, V> occlusion, double minX, double minY, double minZ,
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
        for (EntityProjectionPath<W, P> step = this; step.entrance != null; step = step.parent) {
            step.entrance.sourceToDisplayPoint(out[0], out[1], out[2], out);
        }
    }

    public void point(double x, double y, double z, double[] out) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
        for (EntityProjectionPath<W, P> step = this; step.entrance != null; step = step.parent) {
            step.entrance.sourceToDisplayPoint(out[0], out[1], out[2], out);
        }
        rootPoint(out[0], out[1], out[2], out);
    }

    public void vector(double x, double y, double z, double[] out) {
        out[0] = x;
        out[1] = y;
        out[2] = z;
        for (EntityProjectionPath<W, P> step = this; step.entrance != null; step = step.parent) {
            step.entrance.sourceToDisplayVector(out[0], out[1], out[2], out);
        }
        if (root.mirror()) {
            PortalCoordMap.mirrorSourceToDisplayVectorInto(out[0], out[1], out[2], root.local().getFrame(),
                root.quarterTurns(), out);
        } else {
            root.remoteFrame().transformVectorInto(out[0], out[1], out[2], root.localFrame(), out);
        }
    }

    public boolean upsideDown() {
        vector(0.0D, 1.0D, 0.0D, scratch);
        return scratch[1] < -0.5D;
    }

    public int itemFrameTransform(Direction facing) {
        Direction top = ProjectedItemFrameTransform.canonicalTop(facing);
        Direction right = ProjectedItemFrameTransform.cross(facing, top);
        return ProjectedItemFrameTransform.encode(direction(facing), direction(top), direction(right));
    }

    public <R> R anchor(double x, double y, double z, ProjectedItemFrameTransform.PositionFactory<R> positions) {
        point(Math.floor(x) + 0.5D, Math.floor(y) + 0.5D, Math.floor(z) + 0.5D, scratch);
        double targetX = scratch[0];
        double targetY = scratch[1];
        double targetZ = scratch[2];
        vector(1.0D, 1.0D, 1.0D, scratch);
        double tolerance = ProjectorFrameTransform.coordinateSnapTolerance(x, y, z, targetX, targetY, targetZ);
        return ProjectedItemFrameTransform.anchorPosition(targetX, targetY, targetZ,
            scratch[0], scratch[1], scratch[2], tolerance, positions);
    }

    private Direction direction(Direction source) {
        vector(source.x(), source.y(), source.z(), scratch);
        return Direction.closest(scratch[0], scratch[1], scratch[2]);
    }

    private void rootPoint(double x, double y, double z, double[] out) {
        GeometryVector source = root.remote().getOrigin();
        GeometryVector target = root.local().getOrigin();
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

    public record Root<W, P extends IPortal>(P local, P remote, PortalFrame localFrame, PortalFrame remoteFrame,
                boolean mirror, int quarterTurns, GeometryVector eye, Frustum4D frustum, W world, int recursiveDepth) {
    }
}
