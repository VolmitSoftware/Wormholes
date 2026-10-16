package art.arcane.wormholes.portal.vanilla;

import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.ApertureKind;
import art.arcane.wormholes.portal.DimensionalPortalKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class EndPortalContact {
    public static final String END = "minecraft:the_end";
    public static final double PORTAL_HEIGHT = 0.75D;
    public static final double PLANE_HEIGHT = 0.5D;
    private static final double ENTRY_HEIGHT = 1.0D;
    private static final double EXIT_CLEARANCE = 1.0E-3D;
    private static final double RIM_CENTRE = 0.5D;

    private EndPortalContact() {
    }

    public static boolean applies(DimensionalPortalKind kind, Face normal) {
        return kind == DimensionalPortalKind.END_SOURCE && normal != null && normal.axisIndex() == 1;
    }

    public static boolean contactAperture(ApertureDescriptor geometry) {
        return geometry != null && geometry.kind() == ApertureKind.VANILLA_REPLACEMENT && geometry.facingDirection().axisIndex() == 1;
    }

    public static boolean contactArm(ApertureDescriptor geometry, String destination) {
        return contactAperture(geometry) && END.equals(destination);
    }

    public static boolean returnArm(ApertureDescriptor geometry, String destination) {
        return contactAperture(geometry) && !END.equals(destination);
    }

    public static boolean admitsCrossing(DimensionalPortalKind kind, Face normal, boolean frontSide) {
        return kind != DimensionalPortalKind.END_ARRIVAL || normal == null || normal.axisIndex() != 1
            || (frontSide ? normal.y() : -normal.y()) < 0;
    }

    public static boolean underside(Face normal) {
        return normal.y() < 0;
    }

    public static Vec3d landing(Box area, Box traveller, Predicate<Box> obstructed) {
        if (area == null || traveller == null) {
            return null;
        }
        double minX = Math.round(area.getXa());
        double maxX = Math.round(area.getXb());
        double minZ = Math.round(area.getZa());
        double maxZ = Math.round(area.getZb());
        double halfX = (traveller.getXb() - traveller.getXa()) * 0.5D;
        double halfZ = (traveller.getZb() - traveller.getZa()) * 0.5D;
        double centreX = (traveller.getXa() + traveller.getXb()) * 0.5D;
        double centreZ = (traveller.getZa() + traveller.getZb()) * 0.5D;
        double rise = Math.round(area.getYa()) + ENTRY_HEIGHT - traveller.getYa();
        double alongX = rowCentre(centreX, minX, maxX, halfX) - centreX;
        double alongZ = rowCentre(centreZ, minZ, maxZ, halfZ) - centreZ;
        List<Vec3d> offsets = new ArrayList<>(4);
        offsets.add(new Vec3d(minX - Math.max(halfX, RIM_CENTRE) - centreX, rise, alongZ));
        offsets.add(new Vec3d(maxX + Math.max(halfX, RIM_CENTRE) - centreX, rise, alongZ));
        offsets.add(new Vec3d(alongX, rise, minZ - Math.max(halfZ, RIM_CENTRE) - centreZ));
        offsets.add(new Vec3d(alongX, rise, maxZ + Math.max(halfZ, RIM_CENTRE) - centreZ));
        offsets.sort(Comparator.comparingDouble(offset -> offset.x() * offset.x() + offset.z() * offset.z()));
        for (Vec3d offset : offsets) {
            if (!obstructed.test(new Box(traveller.getXa() + offset.x(), traveller.getXb() + offset.x(), traveller.getYa() + offset.y(),
                traveller.getYb() + offset.y(), traveller.getZa() + offset.z(), traveller.getZb() + offset.z()))) {
                return offset;
            }
        }
        return null;
    }

    public static Hit contact(DimensionalPortalKind kind, Face normal, CellAperture aperture, Box traveller) {
        if (!applies(kind, normal) || aperture == null || traveller == null) {
            return null;
        }
        Box area = aperture.getArea();
        if (area == null) {
            return null;
        }
        int cellY = (int) Math.floor(area.getYa());
        return touches(cellY, traveller) ? hit(aperture, area, cellY, traveller) : null;
    }

    public static Hit contact(ApertureDescriptor geometry, String destination, Box traveller) {
        if (!contactArm(geometry, destination) || traveller == null || !touches(geometry.originY(), traveller)) {
            return null;
        }
        ApertureCells aperture = geometry.aperture();
        Hit hit = hit(aperture, aperture.getArea(), geometry.originY(), traveller);
        if (hit == null) {
            return null;
        }
        double side = geometry.frontSide() ? 1.0D : -1.0D;
        Vec3d entry = hit.entry();
        Vec3d exit = hit.exit();
        return geometry.signedDistance(entry.x(), entry.y(), entry.z()) * side > 0.0D
            && geometry.signedDistance(exit.x(), exit.y(), exit.z()) * side <= 0.0D ? hit : null;
    }

    private static boolean touches(int cellY, Box traveller) {
        return traveller.getYb() > cellY && traveller.getYa() < cellY + PORTAL_HEIGHT;
    }

    private static Hit hit(CellAperture aperture, Box area, int cellY, Box traveller) {
        int minX = Math.max((int) Math.floor(area.getXa()), (int) Math.floor(traveller.getXa()));
        int maxX = Math.min((int) Math.floor(area.getXb()), (int) Math.ceil(traveller.getXb()) - 1);
        int minZ = Math.max((int) Math.floor(area.getZa()), (int) Math.floor(traveller.getZa()));
        int maxZ = Math.min((int) Math.floor(area.getZb()), (int) Math.ceil(traveller.getZb()) - 1);
        double centreX = (traveller.getXa() + traveller.getXb()) * 0.5D;
        double centreZ = (traveller.getZa() + traveller.getZb()) * 0.5D;
        int hitX = 0;
        int hitZ = 0;
        double nearest = Double.POSITIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!aperture.containsBlock(x, cellY, z)) {
                    continue;
                }
                double offsetX = x + 0.5D - centreX;
                double offsetZ = z + 0.5D - centreZ;
                double distance = offsetX * offsetX + offsetZ * offsetZ;
                if (distance < nearest) {
                    nearest = distance;
                    hitX = x;
                    hitZ = z;
                }
            }
        }
        if (nearest == Double.POSITIVE_INFINITY) {
            return null;
        }
        return new Hit(hitX, cellY, hitZ, new Vec3d(insideCell(centreX, hitX), traveller.getYa(), insideCell(centreZ, hitZ)));
    }

    private static double rowCentre(double centre, double minimum, double maximum, double half) {
        return maximum - minimum < half * 2.0D ? (minimum + maximum) * 0.5D : Math.clamp(centre, minimum + half, maximum - half);
    }

    private static double insideCell(double value, int cell) {
        return Math.clamp(value, cell, Math.nextDown(cell + 1.0D));
    }

    public record Hit(int cellX, int cellY, int cellZ, Vec3d point) {
        public Vec3d entry() {
            return new Vec3d(point.x(), cellY + ENTRY_HEIGHT, point.z());
        }

        public Vec3d exit() {
            return new Vec3d(point.x(), Math.min(point.y(), cellY + PLANE_HEIGHT - EXIT_CLEARANCE), point.z());
        }
    }
}
