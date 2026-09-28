package art.arcane.wormholes.transit;


import art.arcane.wormholes.geometry.GeometryVector;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalCrossing;
import art.arcane.wormholes.util.Direction;

/**
 * Derives the traveler's exit look from an {@link OrientationPolicy}. Frame-relative policies (FRAME,
 * MIRROR, SNAP) get the gravity flip on vertical exits: the look is rotated about the exit frame's right
 * axis so the frame's up axis lands on world up, which is where the game will put the traveler's feet.
 * LOOK is absolute and never flipped.
 */
public final class OrientationTransform {
    private static final double TWO_PI = 2.0D * Math.PI;
    private static final GeometryVector WORLD_UP = new GeometryVector(0.0D, 1.0D, 0.0D);

    private OrientationTransform() {
    }

    /** Bukkit yaw (0 = south, 90 = west) and pitch (-90 = up) for an exit direction. */
    public record Look(float yaw, float pitch) {
        public static Look of(GeometryVector direction) {
            double x = direction.getX();
            double y = direction.getY();
            double z = direction.getZ();
            if (x == 0.0D && z == 0.0D) {
                return new Look(0.0F, y > 0.0D ? -90.0F : 90.0F);
            }
            double theta = Math.atan2(-x, z);
            float yaw = (float) Math.toDegrees((theta + TWO_PI) % TWO_PI);
            float pitch = (float) Math.toDegrees(Math.atan(-y / Math.sqrt(x * x + z * z)));
            return new Look(yaw, pitch);
        }
    }

    public static Look apply(PortalCrossing traversive, PortalFrame outFrame, OrientationPolicy policy, boolean gravityFlip) {
        return Look.of(direction(traversive, outFrame, policy, gravityFlip));
    }

    public static GeometryVector direction(PortalCrossing traversive, PortalFrame outFrame, OrientationPolicy policy, boolean gravityFlip) {
        OrientationPolicy active = policy == null ? OrientationPolicy.FRAME : policy;
        PortalFrame outView = outFrame.view(traversive.frontSide());
        GeometryVector look = switch (active) {
            case FRAME -> traversive.outLook(outFrame);
            case LOOK -> traversive.look();
            case SNAP -> vector(outView.getNormal()).multiply(-1.0D);
            case MIRROR -> reflectAcrossPlane(traversive.outLook(outFrame), outView.getNormal());
        };
        if (active != OrientationPolicy.LOOK && gravityFlip && outView.getNormal().isVertical()) {
            return flipUpright(look, outView);
        }
        return look;
    }

    private static GeometryVector reflectAcrossPlane(GeometryVector look, Direction normal) {
        GeometryVector unit = vector(normal);
        double along = dot(look, unit);
        return look.subtract(unit.multiply(2.0D * along));
    }

    /**
     * Rotates {@code look} about the exit frame's right axis so the frame's up axis maps onto world up.
     * With exit travel direction e = -normal (vertical) and s = e dot worldUp, the rotation sends up to
     * worldUp and e to -s * up.
     */
    private static GeometryVector flipUpright(GeometryVector look, PortalFrame outView) {
        GeometryVector right = vector(outView.getRight());
        GeometryVector up = vector(outView.getUp());
        GeometryVector exitDirection = vector(outView.getNormal()).multiply(-1.0D);
        double sign = dot(exitDirection, WORLD_UP);
        double alongRight = dot(look, right);
        double alongUp = dot(look, up);
        double alongExit = dot(look, exitDirection);
        return right.multiply(alongRight)
            .add(WORLD_UP.multiply(alongUp))
            .add(up.multiply(-sign * alongExit));
    }
    private static GeometryVector vector(Direction direction) {
        return new GeometryVector(direction.x(), direction.y(), direction.z());
    }

    private static double dot(GeometryVector a, GeometryVector b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }
}
