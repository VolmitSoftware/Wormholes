package art.arcane.wormholes.render.client.session;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;

import java.util.Arrays;
import java.util.Objects;

public final class SeamlessCrossCheck {
    private static final double BASE_TOLERANCE = 4.0D;
    private static final double SPEED_TOLERANCE = 1.5D;
    private static final double MAX_TOLERANCE = 12.0D;
    private static final double BASE_SEGMENT = 4.0D;
    private static final double SPEED_SEGMENT = 2.0D;
    private static final double EYE_DRIFT = 0.125D;
    private static final double EYE_SLACK = 0.5D;

    private SeamlessCrossCheck() {
    }

    public static Refusal check(TravelMessage.TravelCross cross, TravelMessage.TravelBegin arm, Server server) {
        Objects.requireNonNull(cross, "cross");
        Objects.requireNonNull(arm, "arm");
        Objects.requireNonNull(server, "server");
        if (server.awaitingTeleport()) {
            return Refusal.AWAITING_TELEPORT;
        }
        if (server.changingDimension()) {
            return Refusal.CHANGING_DIMENSION;
        }
        if (!arm.sourceWorld().equals(server.world())) {
            return Refusal.WRONG_LEVEL;
        }
        ApertureDescriptor geometry = arm.sourceGeometry();
        if (!sameSurface(geometry, server.geometry())) {
            return Refusal.CHANGED_SURFACE;
        }
        double speed = server.velocity().distance(new Vec3d(0, 0, 0));
        double tolerance = Math.min(MAX_TOLERANCE, BASE_TOLERANCE + speed * SPEED_TOLERANCE);
        TravelMessage.TravelPose claimed = cross.sourcePose();
        Vec3d feet = new Vec3d(claimed.x(), claimed.y(), claimed.z());
        Vec3d observed = new Vec3d(server.pose().x(), server.pose().y(), server.pose().z());
        if (feet.distance(observed) > tolerance) {
            return Refusal.FAR_FROM_SERVER;
        }
        if (cross.previousEye().distance(cross.currentEye()) > BASE_SEGMENT + speed * SPEED_SEGMENT) {
            return Refusal.SEGMENT_TOO_LONG;
        }
        double eyeRise = cross.currentEye().y() - feet.y();
        double drift = Math.hypot(cross.currentEye().x() - feet.x(), cross.currentEye().z() - feet.z());
        if (drift > EYE_DRIFT || eyeRise < 0.0D || eyeRise > server.eyeHeight() + EYE_SLACK) {
            return Refusal.INCONSISTENT_EYE;
        }
        double side = geometry.frontSide() ? 1.0D : -1.0D;
        double before = geometry.signedDistance(cross.previousEye().x(), cross.previousEye().y(), cross.previousEye().z()) * side;
        double after = geometry.signedDistance(cross.currentEye().x(), cross.currentEye().y(), cross.currentEye().z()) * side;
        if (before <= 0.0D) {
            return Refusal.WRONG_SIDE;
        }
        if (after > 0.0D) {
            return Refusal.NO_CROSSING;
        }
        Vec3d intersection = cross.previousEye().add(cross.currentEye().subtract(cross.previousEye()).multiply(before / (before - after)));
        return geometry.containsPoint(intersection.x(), intersection.y(), intersection.z()) ? Refusal.NONE : Refusal.OUTSIDE_APERTURE;
    }

    public static boolean sameSurface(ApertureDescriptor armed, ApertureDescriptor current) {
        return current != null && !current.mirror() && armed.originX() == current.originX() && armed.originY() == current.originY()
            && armed.originZ() == current.originZ() && armed.facing() == current.facing() && armed.frontSide() == current.frontSide()
            && armed.quarterTurns() == current.quarterTurns() && armed.kind() == current.kind()
            && armed.apertureWidth() == current.apertureWidth() && armed.apertureHeight() == current.apertureHeight()
            && Arrays.equals(armed.apertureMask(), current.apertureMask()) && armed.shape().equals(current.shape());
    }

    public enum Refusal {
        NONE, AWAITING_TELEPORT, CHANGING_DIMENSION, WRONG_LEVEL, CHANGED_SURFACE, FAR_FROM_SERVER, SEGMENT_TOO_LONG, INCONSISTENT_EYE,
        WRONG_SIDE, NO_CROSSING, OUTSIDE_APERTURE
    }

    public record Server(String world, ApertureDescriptor geometry, TravelMessage.TravelPose pose, Vec3d velocity, double eyeHeight,
                         boolean awaitingTeleport, boolean changingDimension) {
        public Server {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(velocity, "velocity");
            if (!Double.isFinite(eyeHeight) || eyeHeight <= 0.0D || eyeHeight > 4.0D || !Double.isFinite(velocity.x())
                || !Double.isFinite(velocity.y()) || !Double.isFinite(velocity.z())) {
                throw new IllegalArgumentException("Seamless crossing authority");
            }
        }
    }
}
