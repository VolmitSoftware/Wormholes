package art.arcane.optics.recursion;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.aperture.Endpoint;
import art.arcane.optics.view.BlockView;

import art.arcane.optics.entity.EntitySnapshot;

import art.arcane.optics.math.Box;
import art.arcane.optics.occlusion.EntityOcclusion;
import art.arcane.optics.volume.ViewVolume;
import art.arcane.optics.frame.OpticTransform;

public final class EntityPath<W, P extends Endpoint> {
    private final EntityPath<W, P> parent;
    private final RecursiveEndpoints<W, P>.Candidate entrance;
    private final Root<W, P> root;
    private final OpticTransform chain;
    private final OpticTransform transform;
    private final double[] scratch = new double[3];
    public final RecursiveEndpoints<W, P>.Index index;
    public final int remainingDepth;

    public EntityPath(Root<W, P> root, RecursiveEndpoints<W, P> portals) {
        this.parent = null;
        this.entrance = null;
        this.root = root;
        this.remainingDepth = root.recursiveDepth();
        this.chain = OpticTransform.IDENTITY;
        this.transform = root.transform();
        Vec3d eye = root.eye();
        root.transform().inverse().pointInto(eye.getX(), eye.getY(), eye.getZ(), scratch);
        this.index = portals.indexFor(root.world(), scratch[0], scratch[1], scratch[2], root.remote());
    }

    private EntityPath(EntityPath<W, P> parent, RecursiveEndpoints<W, P>.Candidate entrance,
                                  RecursiveEndpoints<W, P> portals) {
        this.parent = parent;
        this.entrance = entrance;
        this.root = parent.root;
        this.remainingDepth = parent.remainingDepth - 1;
        this.chain = parent.chain.compose(entrance.transform());
        this.transform = root.transform().compose(chain);
        this.index = portals.indexFor(entrance.nestedWorld, entrance.transformedEyeX, entrance.transformedEyeY,
            entrance.transformedEyeZ, entrance.nestedDestination);
    }

    public EntityPath<W, P> child(RecursiveEndpoints<W, P>.Candidate candidate, RecursiveEndpoints<W, P> portals) {
        if (remainingDepth <= 0 || !candidate.traversable) {
            return null;
        }
        for (EntityPath<W, P> step = this; step.entrance != null; step = step.parent) {
            if (step.entrance.portalId.equals(candidate.portalId)) {
                return null;
            }
        }
        return intersects(candidate.view) ? new EntityPath<>(this, candidate, portals) : null;
    }

    public boolean nested() {
        return parent != null;
    }

    public OpticTransform transform() {
        return transform;
    }

    public boolean visible(EntitySnapshot visual, double centerY, double[] out) {
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
        for (EntityPath<W, P> step = this; step.entrance != null; step = step.parent) {
            step.entrance.transform().pointInto(out[0], out[1], out[2], out);
            RecursiveEndpoints.Hit<W, P> hit = step.parent.index.find(out[0], out[1], out[2], step.parent.remainingDepth);
            if (hit == null || !hit.traversable || !step.entrance.portalId.equals(hit.portalId)) {
                return false;
            }
        }
        root.transform().pointInto(out[0], out[1], out[2], out);
        return root.frustum().containsPrimitive(out[0], out[1], out[2]);
    }

    public <B, V extends BlockView<B>> boolean fullyHidden(EntityOcclusion<B, V> occlusion, double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ) {
        chain.pointInto(minX, minY, minZ, scratch);
        double x = scratch[0];
        double y = scratch[1];
        double z = scratch[2];
        chain.pointInto(maxX, maxY, maxZ, scratch);
        return occlusion.fullyHidden(Math.min(x, scratch[0]), Math.min(y, scratch[1]), Math.min(z, scratch[2]),
            Math.max(x, scratch[0]), Math.max(y, scratch[1]), Math.max(z, scratch[2]));
    }

    public void point(double x, double y, double z, double[] out) {
        transform.pointInto(x, y, z, out);
    }

    private boolean intersects(Box view) {
        Box region = root.frustum().getRegion();
        point(view.getXa(), view.getYa(), view.getZa(), scratch);
        double x = scratch[0];
        double y = scratch[1];
        double z = scratch[2];
        point(view.getXb(), view.getYb(), view.getZb(), scratch);
        return Math.min(x, scratch[0]) <= region.getXb() && Math.max(x, scratch[0]) >= region.getXa()
            && Math.min(y, scratch[1]) <= region.getYb() && Math.max(y, scratch[1]) >= region.getYa()
            && Math.min(z, scratch[2]) <= region.getZb() && Math.max(z, scratch[2]) >= region.getZa();
    }

    public record Root<W, P extends Endpoint>(P local, P remote, OpticTransform transform, Vec3d eye, ViewVolume frustum, W world,
                                              int recursiveDepth) {
    }
}
