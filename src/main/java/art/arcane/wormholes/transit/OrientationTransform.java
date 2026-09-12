package art.arcane.wormholes.transit;

import org.bukkit.util.Vector;

import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.util.Direction;

/**
 * Derives the traveler's exit look from an {@link OrientationPolicy}. Frame-relative policies (FRAME,
 * MIRROR, SNAP) get the gravity flip on vertical exits: the look is rotated about the exit frame's right
 * axis so the frame's up axis lands on world up, which is where the game will put the traveler's feet.
 * LOOK is absolute and never flipped.
 */
public final class OrientationTransform {
    private static final double TWO_PI = 2.0D * Math.PI;
    private static final Vector WORLD_UP = new Vector(0.0D, 1.0D, 0.0D);

    private OrientationTransform() {
    }

    /** Bukkit yaw (0 = south, 90 = west) and pitch (-90 = up) for an exit direction. */
    public record Look(float yaw, float pitch) {
        public static Look of(Vector direction) {
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

    public static Look apply(Traversive traversive, PortalFrame outFrame, OrientationPolicy policy, boolean gravityFlip) {
        return Look.of(direction(traversive, outFrame, policy, gravityFlip));
    }

    public static Vector direction(Traversive traversive, PortalFrame outFrame, OrientationPolicy policy, boolean gravityFlip) {
        OrientationPolicy active = policy == null ? OrientationPolicy.FRAME : policy;
        PortalFrame outView = outFrame.view(traversive.isFrontSide());
        Vector look = switch (active) {
            case FRAME -> traversive.getOutLook(outFrame);
            case LOOK -> traversive.getInLook().clone();
            case SNAP -> outView.getNormal().toVector().multiply(-1.0D);
            case MIRROR -> reflectAcrossPlane(traversive.getOutLook(outFrame), outView.getNormal());
        };
        if (active != OrientationPolicy.LOOK && gravityFlip && outView.getNormal().isVertical()) {
            return flipUpright(look, outView);
        }
        return look;
    }

    private static Vector reflectAcrossPlane(Vector look, Direction normal) {
        Vector unit = normal.toVector();
        double along = look.dot(unit);
        return look.clone().subtract(unit.multiply(2.0D * along));
    }

    /**
     * Rotates {@code look} about the exit frame's right axis so the frame's up axis maps onto world up.
     * With exit travel direction e = -normal (vertical) and s = e dot worldUp, the rotation sends up to
     * worldUp and e to -s * up.
     */
    private static Vector flipUpright(Vector look, PortalFrame outView) {
        Vector right = outView.getRight().toVector();
        Vector up = outView.getUp().toVector();
        Vector exitDirection = outView.getNormal().toVector().multiply(-1.0D);
        double sign = exitDirection.dot(WORLD_UP);
        double alongRight = look.dot(right);
        double alongUp = look.dot(up);
        double alongExit = look.dot(exitDirection);
        return right.multiply(alongRight)
            .add(WORLD_UP.clone().multiply(alongUp))
            .add(up.multiply(-sign * alongExit));
    }
}
