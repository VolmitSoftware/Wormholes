package art.arcane.wormholes;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;

import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;

final class ProjectionPortalOcclusion {
    private static final double EPSILON = 1.0E-7D;

    private ProjectionPortalOcclusion() {
    }

    static boolean isFullyOccluded(Location eye,
                                   List<ILocalPortal> nearerPortals,
                                   ILocalPortal candidate) {
        if (eye == null || nearerPortals == null || candidate == null) {
            return false;
        }
        int nearerCount = nearerPortals.size();
        for (int index = 0; index < nearerCount; index++) {
            if (fullyOccludes(eye, nearerPortals.get(index), candidate)) {
                return true;
            }
        }
        return false;
    }

    static boolean fullyOccludes(Location eye, ILocalPortal nearer, ILocalPortal farther) {
        if (eye == null || nearer == null || farther == null || nearer == farther) {
            return false;
        }
        World eyeWorld = eye.getWorld();
        World nearerWorld = nearer.getWorld();
        World fartherWorld = farther.getWorld();
        if (eyeWorld == null || nearerWorld == null || fartherWorld == null
            || !eyeWorld.equals(nearerWorld) || !eyeWorld.equals(fartherWorld)) {
            return false;
        }
        PortalStructure nearerStructure = nearer.getStructure();
        PortalStructure fartherStructure = farther.getStructure();
        PortalFrame nearerFrame = nearer.getFrame();
        if (nearerStructure == null || fartherStructure == null || nearerFrame == null
            || !nearerStructure.isFullCuboid()) {
            return false;
        }
        AxisAlignedBB nearerArea = nearerStructure.getArea();
        AxisAlignedBB fartherArea = fartherStructure.getArea();
        if (nearerArea == null || fartherArea == null) {
            return false;
        }
        return fullyOccludes(
            eye.getX(), eye.getY(), eye.getZ(),
            nearerArea, nearerFrame.getNormal().getAxis(), fartherArea);
    }

    static boolean fullyOccludes(double eyeX,
                                 double eyeY,
                                 double eyeZ,
                                 AxisAlignedBB nearer,
                                 Axis nearerAxis,
                                 AxisAlignedBB farther) {
        if (nearer == null || nearerAxis == null || farther == null) {
            return false;
        }
        double eyeAxis = coordinate(eyeX, eyeY, eyeZ, nearerAxis);
        double nearerMinimum = minimum(nearer, nearerAxis);
        double nearerMaximum = maximum(nearer, nearerAxis);
        double plane;
        if (eyeAxis < nearerMinimum - EPSILON) {
            plane = nearerMinimum;
        } else if (eyeAxis > nearerMaximum + EPSILON) {
            plane = nearerMaximum;
        } else {
            return false;
        }

        for (int corner = 0; corner < 8; corner++) {
            double targetX = (corner & 1) == 0 ? farther.getXa() : farther.getXb();
            double targetY = (corner & 2) == 0 ? farther.getYa() : farther.getYb();
            double targetZ = (corner & 4) == 0 ? farther.getZa() : farther.getZb();
            if (!rayCrossesAperture(
                eyeX, eyeY, eyeZ, targetX, targetY, targetZ,
                nearer, nearerAxis, plane, eyeAxis)) {
                return false;
            }
        }
        return true;
    }

    private static boolean rayCrossesAperture(double eyeX,
                                              double eyeY,
                                              double eyeZ,
                                              double targetX,
                                              double targetY,
                                              double targetZ,
                                              AxisAlignedBB nearer,
                                              Axis nearerAxis,
                                              double plane,
                                              double eyeAxis) {
        double targetAxis = coordinate(targetX, targetY, targetZ, nearerAxis);
        double denominator = targetAxis - eyeAxis;
        if (Math.abs(denominator) <= EPSILON) {
            return false;
        }
        double distance = (plane - eyeAxis) / denominator;
        if (distance <= EPSILON || distance >= 1.0D - EPSILON) {
            return false;
        }
        double hitX = eyeX + ((targetX - eyeX) * distance);
        double hitY = eyeY + ((targetY - eyeY) * distance);
        double hitZ = eyeZ + ((targetZ - eyeZ) * distance);
        return hitX >= nearer.getXa() - EPSILON && hitX <= nearer.getXb() + EPSILON
            && hitY >= nearer.getYa() - EPSILON && hitY <= nearer.getYb() + EPSILON
            && hitZ >= nearer.getZa() - EPSILON && hitZ <= nearer.getZb() + EPSILON;
    }

    private static double coordinate(double x, double y, double z, Axis axis) {
        return switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
    }

    private static double minimum(AxisAlignedBB box, Axis axis) {
        return switch (axis) {
            case X -> box.getXa();
            case Y -> box.getYa();
            case Z -> box.getZa();
        };
    }

    private static double maximum(AxisAlignedBB box, Axis axis) {
        return switch (axis) {
            case X -> box.getXb();
            case Y -> box.getYb();
            case Z -> box.getZb();
        };
    }
}
