package art.arcane.wormholes.render;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.Axis;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class Frustum4D {
    private static final double EPSILON = 1.0E-7D;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Frustum.FaceIndex[] NO_FACE_INDEX = new Frustum.FaceIndex[0];
    private static final Frustum4D EMPTY = new Frustum4D();

    private final Frustum[] frustums;
    private volatile Frustum.FaceIndex[] faceIndex;
    private final AxisAlignedBB region;
    private final double regionXa;
    private final double regionXb;
    private final double regionYa;
    private final double regionYb;
    private final double regionZa;
    private final double regionZb;

    private Frustum4D() {
        this.frustums = new Frustum[0];
        this.faceIndex = NO_FACE_INDEX;
        this.region = new AxisAlignedBB(0.25D, 0.25D, 0.25D, 0.25D, 0.25D, 0.25D);
        this.regionXa = region.getXa();
        this.regionXb = region.getXb();
        this.regionYa = region.getYa();
        this.regionYb = region.getYb();
        this.regionZa = region.getZa();
        this.regionZb = region.getZb();
    }

    public Frustum4D(Location iris, PortalStructure structure, double axialRange, double lateralRange) {
        AxisAlignedBB aperture = structure.getArea();
        Vector apertureCenter = aperture.center();
        Axis thinAxis = aperture.getThinAxis();
        double portalToEyeRawX = thinAxis == Axis.X || thinAxis == null
            ? iris.getX() - apertureCenter.getX()
            : iris.getX() - clamp(iris.getX(), aperture.getXa(), aperture.getXb());
        double portalToEyeRawY = thinAxis == Axis.Y || thinAxis == null
            ? iris.getY() - apertureCenter.getY()
            : iris.getY() - clamp(iris.getY(), aperture.getYa(), aperture.getYb());
        double portalToEyeRawZ = thinAxis == Axis.Z || thinAxis == null
            ? iris.getZ() - apertureCenter.getZ()
            : iris.getZ() - clamp(iris.getZ(), aperture.getZa(), aperture.getZb());
        double distanceToPortal = Math.sqrt((portalToEyeRawX * portalToEyeRawX)
            + (portalToEyeRawY * portalToEyeRawY)
            + (portalToEyeRawZ * portalToEyeRawZ));
        if (distanceToPortal <= EPSILON) {
            Location center = structure.getCenter();
            portalToEyeRawX = iris.getX() - center.getX();
            portalToEyeRawY = iris.getY() - center.getY();
            portalToEyeRawZ = iris.getZ() - center.getZ();
            distanceToPortal = Math.sqrt((portalToEyeRawX * portalToEyeRawX)
                + (portalToEyeRawY * portalToEyeRawY)
                + (portalToEyeRawZ * portalToEyeRawZ));
        }
        double inverseDistance = distanceToPortal <= EPSILON ? 0.0D : 1.0D / distanceToPortal;
        double portalToEyeX = portalToEyeRawX * inverseDistance;
        double portalToEyeY = portalToEyeRawY * inverseDistance;
        double portalToEyeZ = portalToEyeRawZ * inverseDistance;

        double padding = Settings.NEAR_PLANE_PADDING;
        Location apex;
        if (padding > 0.0001D && distanceToPortal > 0.0001D) {
            Vector backOffset = new Vector(portalToEyeX * padding, portalToEyeY * padding, portalToEyeZ * padding);
            apex = iris.clone().add(backOffset);
        } else {
            apex = iris;
        }

        double cullRatio = Settings.FRUSTUM_CULLING_RATIO;
        double aperturePadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        KList<Frustum> built = new KList<Frustum>();
        for (Direction face : DIRECTIONS) {
            if (face.x() == 1 && portalToEyeX > cullRatio) {
                addFrustums(built, apex, structure, face, thinAxis, axialRange, lateralRange, aperturePadding);
                continue;
            }
            if (face.x() == -1 && portalToEyeX < -cullRatio) {
                addFrustums(built, apex, structure, face, thinAxis, axialRange, lateralRange, aperturePadding);
                continue;
            }
            if (face.y() == 1 && portalToEyeY > cullRatio) {
                addFrustums(built, apex, structure, face, thinAxis, axialRange, lateralRange, aperturePadding);
                continue;
            }
            if (face.y() == -1 && portalToEyeY < -cullRatio) {
                addFrustums(built, apex, structure, face, thinAxis, axialRange, lateralRange, aperturePadding);
                continue;
            }
            if (face.z() == 1 && portalToEyeZ > cullRatio) {
                addFrustums(built, apex, structure, face, thinAxis, axialRange, lateralRange, aperturePadding);
                continue;
            }
            if (face.z() == -1 && portalToEyeZ < -cullRatio) {
                addFrustums(built, apex, structure, face, thinAxis, axialRange, lateralRange, aperturePadding);
            }
        }

        if (built.isEmpty()) {
            Direction fallback = Direction.closest(-portalToEyeX, -portalToEyeY, -portalToEyeZ);
            addFrustums(built, apex, structure, fallback, thinAxis, axialRange, lateralRange, aperturePadding);
        }

        this.frustums = built.toArray(new Frustum[built.size()]);
        this.faceIndex = frustums.length < 4 ? NO_FACE_INDEX : null;

        AxisAlignedBB acc = new AxisAlignedBB(this.frustums[0].getRegion());
        for (int i = 1; i < this.frustums.length; i++) {
            acc.encapsulate(this.frustums[i].getRegion());
        }
        this.region = acc;
        this.regionXa = acc.getXa();
        this.regionXb = acc.getXb();
        this.regionYa = acc.getYa();
        this.regionYb = acc.getYb();
        this.regionZa = acc.getZa();
        this.regionZb = acc.getZb();
    }

    static Frustum4D empty() {
        return EMPTY;
    }

    public boolean contains(Vector p) {
        return containsPrimitive(p.getX(), p.getY(), p.getZ());
    }

    public boolean contains(Location p) {
        return containsPrimitive(p.getX(), p.getY(), p.getZ());
    }

    public boolean containsPrimitive(double x, double y, double z) {
        if (x < regionXa || x > regionXb || y < regionYa || y > regionYb || z < regionZa || z > regionZb) {
            return false;
        }
        Frustum.FaceIndex[] indexedFaces = faceIndex;
        if (indexedFaces == null) {
            indexedFaces = initializeFaceIndex();
        }
        if (indexedFaces.length > 0) {
            for (Frustum.FaceIndex group : indexedFaces) {
                if (group.contains(x, y, z)) {
                    return true;
                }
            }
            return false;
        }
        Frustum[] arr = frustums;
        int count = arr.length;
        for (int i = 0; i < count; i++) {
            if (arr[i].containsPrimitive(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    boolean containsBox(double minX,
                        double minY,
                        double minZ,
                        double maxX,
                        double maxY,
                        double maxZ) {
        if (minX < regionXa || maxX > regionXb
            || minY < regionYa || maxY > regionYb
            || minZ < regionZa || maxZ > regionZb) {
            return false;
        }
        return containsPrimitive(minX, minY, minZ)
            && containsPrimitive(minX, minY, maxZ)
            && containsPrimitive(minX, maxY, minZ)
            && containsPrimitive(minX, maxY, maxZ)
            && containsPrimitive(maxX, minY, minZ)
            && containsPrimitive(maxX, minY, maxZ)
            && containsPrimitive(maxX, maxY, minZ)
            && containsPrimitive(maxX, maxY, maxZ);
    }

    boolean containsRow(int axis, double x, double y, double z, double end) {
        if (frustums.length > 3) {
            return false;
        }
        for (Frustum frustum : frustums) {
            if (frustum.containsRow(axis, x, y, z, end)) {
                return true;
            }
        }
        return false;
    }

    boolean appendRow(ProjectorFrustumRow row, int axis, double x, double y, double z, int minimum, int maximum) {
        if (frustums.length > 128) {
            return false;
        }
        for (Frustum frustum : frustums) {
            if (!frustum.appendRow(row, axis, x, y, z, minimum, maximum)) {
                return false;
            }
        }
        return true;
    }

    public AxisAlignedBB getRegion() {
        return region;
    }

    public int getFaceCount() {
        return frustums.length;
    }

    private synchronized Frustum.FaceIndex[] initializeFaceIndex() {
        if (faceIndex == null) {
            Frustum.FaceIndex[] built = Frustum.FaceIndex.build(frustums);
            faceIndex = built == null ? NO_FACE_INDEX : built;
        }
        return faceIndex;
    }

    private static double clamp(double value, double low, double high) {
        return value < low ? low : (value > high ? high : value);
    }

    private static void addFrustums(KList<Frustum> frustums,
                                    Location apex,
                                    PortalStructure structure,
                                    Direction face,
                                    Axis portalNormalAxis,
                                    double axialRange,
                                    double lateralRange,
                                    double aperturePadding) {
        List<AxisAlignedBB> apertureFaces = structure.getCachedApertureFaces(face);
        for (AxisAlignedBB apertureFace : apertureFaces) {
            frustums.add(new Frustum(apex, apertureFace, face, portalNormalAxis, axialRange, lateralRange,
                aperturePadding));
        }
    }
}
