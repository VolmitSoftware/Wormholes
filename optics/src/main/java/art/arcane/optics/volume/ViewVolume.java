package art.arcane.optics.volume;

import art.arcane.optics.math.Vec3;

import java.util.List;

import java.util.ArrayList;
import art.arcane.optics.aperture.Aperture;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;

public final class ViewVolume {
    private static final double EPSILON = 1.0E-7D;
    private static final Face[] DIRECTIONS = Face.values();
    private static final Frustum.FaceIndex[] NO_FACE_INDEX = new Frustum.FaceIndex[0];
    private static final ViewVolume EMPTY = new ViewVolume();

    private final Frustum[] frustums;
    private volatile Frustum.FaceIndex[] faceIndex;
    private final Box region;
    private final double regionXa;
    private final double regionXb;
    private final double regionYa;
    private final double regionYb;
    private final double regionZa;
    private final double regionZb;

    private ViewVolume() {
        this.frustums = new Frustum[0];
        this.faceIndex = NO_FACE_INDEX;
        this.region = new Box(0.25D, 0.25D, 0.25D, 0.25D, 0.25D, 0.25D);
        this.regionXa = region.getXa();
        this.regionXb = region.getXb();
        this.regionYa = region.getYa();
        this.regionYb = region.getYb();
        this.regionZa = region.getZa();
        this.regionZb = region.getZb();
    }

    public ViewVolume(Vec3 iris, Aperture structure, Options options) {
        double axialRange = options.axialRange();
        double lateralRange = options.lateralRange();
        Box aperture = structure.getArea();
        Vec3 apertureCenter = aperture.center();
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
            Vec3 center = structure.getApertureCenter();
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

        double padding = options.nearPlanePadding();
        Vec3 apex;
        if (padding > 0.0001D && distanceToPortal > 0.0001D) {
            Vec3 backOffset = new Vec3(portalToEyeX * padding, portalToEyeY * padding, portalToEyeZ * padding);
            apex = iris.add(backOffset);
        } else {
            apex = iris;
        }

        double cullRatio = options.cullingRatio();
        double aperturePadding = options.aperturePadding();
        List<Frustum> built = new ArrayList<Frustum>(6);
        for (Face face : DIRECTIONS) {
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
            Face fallback = Face.closest(-portalToEyeX, -portalToEyeY, -portalToEyeZ);
            addFrustums(built, apex, structure, fallback, thinAxis, axialRange, lateralRange, aperturePadding);
        }

        this.frustums = built.toArray(new Frustum[built.size()]);
        this.faceIndex = frustums.length < 4 ? NO_FACE_INDEX : null;

        Box acc = new Box(this.frustums[0].getRegion());
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

    static ViewVolume empty() {
        return EMPTY;
    }

    public boolean contains(Vec3 p) {
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
        if (!(Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
            && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ))
            || minX > maxX || minY > maxY || minZ > maxZ) {
            return false;
        }
        for (Frustum frustum : frustums) {
            if (frustum.containsBox(minX, minY, minZ, maxX, maxY, maxZ)) {
                return true;
            }
        }
        return frustums.length > 1
            && Frustum.containsBoxUnion(frustums, minX, minY, minZ, maxX, maxY, maxZ);
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

    public Box getRegion() {
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

    private static void addFrustums(List<Frustum> frustums,
                                    Vec3 apex,
                                    Aperture structure,
                                    Face face,
                                    Axis portalNormalAxis,
                                    double axialRange,
                                    double lateralRange,
                                    double aperturePadding) {
        List<Box> apertureFaces = structure.getCachedApertureFaces(face);
        for (Box apertureFace : apertureFaces) {
            frustums.add(new Frustum(apex, apertureFace, face, portalNormalAxis, axialRange, lateralRange,
                aperturePadding));
        }
    }

    public record Options(double axialRange, double lateralRange, double nearPlanePadding,
                          double cullingRatio, double aperturePadding) {
    }
}
