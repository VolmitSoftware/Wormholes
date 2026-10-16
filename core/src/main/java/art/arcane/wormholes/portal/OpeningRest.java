package art.arcane.wormholes.portal;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;

import java.util.function.Predicate;

public final class OpeningRest {
    private static final double PROBE = 1.0E-3D;
    private static final double EXIT_CLEARANCE = 1.0E-3D;

    private OpeningRest() {
    }

    public static boolean applies(ApertureDescriptor geometry) {
        return geometry != null && geometry.kind() == ApertureKind.FRAME && !geometry.mirror() && geometry.facingDirection().axisIndex() == 1;
    }

    public static Rest rest(ApertureDescriptor geometry, Box traveller, Vec3d eye, Predicate<Box> obstructed) {
        if (!applies(geometry) || traveller == null || eye == null) {
            return null;
        }
        double side = geometry.frontSide() ? 1.0D : -1.0D;
        double eyeDistance = geometry.signedDistance(eye.x(), eye.y(), eye.z()) * side;
        double travel = -geometry.facingDirection().y() * side;
        int cellY = geometry.originY();
        boolean entered = travel < 0.0D ? traveller.getYa() < cellY + 1.0D : traveller.getYb() > cellY;
        if (eyeDistance <= 0.0D || !entered || !geometry.containsPoint(eye.x(), geometry.planeCoordinate(), eye.z())
            || obstructed.test(traveller) || !obstructed.test(shifted(traveller, travel * PROBE))) {
            return null;
        }
        return new Rest(travel * (eyeDistance + EXIT_CLEARANCE));
    }

    private static Box shifted(Box box, double y) {
        return new Box(box.getXa(), box.getXb(), box.getYa() + y, box.getYb() + y, box.getZa(), box.getZb());
    }

    public record Rest(double shift) {
        public Vec3d exit(Vec3d feet) {
            return new Vec3d(feet.x(), feet.y() + shift, feet.z());
        }
    }
}
