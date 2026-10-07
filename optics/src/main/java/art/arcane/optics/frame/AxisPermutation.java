package art.arcane.optics.frame;

import art.arcane.optics.internal.frame.Rotation16;
import java.util.Arrays;
import java.util.Objects;

import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Face;

public final class AxisPermutation {
    private static final int COUNT = 48;
    private static final int FACE_COUNT = 6;
    private static final Face[] ORDER = {Face.E, Face.W, Face.U, Face.D, Face.S, Face.N};
    private static final AxisPermutation[] VALUES = enumerate();
    private static final int[] INDEX = indexByImages(VALUES);
    public static final AxisPermutation IDENTITY = VALUES[0];

    private final int index;
    private final Face x;
    private final Face y;
    private final Face z;
    private final Face[] images;
    private final Face inverseX;
    private final Face inverseY;
    private final Face inverseZ;
    private final int sourceX;
    private final int sourceY;
    private final int sourceZ;
    private final double signX;
    private final double signY;
    private final double signZ;
    private final boolean reflects;
    private final boolean reflectsHorizontally;
    private final int quarterTurnsClockwise;

    private AxisPermutation(int index, Face x, Face y, Face z) {
        this.index = index;
        this.x = x;
        this.y = y;
        this.z = z;
        Face[] axisImages = {x, y, z};
        images = new Face[FACE_COUNT];
        for (Face face : Face.values()) {
            Face image = axisImages[face.getAxis().ordinal()];
            images[face.ordinal()] = face.sign() > 0 ? image : image.reverse();
        }
        int[] sources = new int[3];
        double[] signs = new double[3];
        Face[] inverseImages = new Face[3];
        for (int source = 0; source < 3; source++) {
            Face image = axisImages[source];
            int target = image.getAxis().ordinal();
            sources[target] = source;
            signs[target] = image.sign();
            Face sourceFace = ORDER[source * 2];
            inverseImages[target] = image.sign() > 0 ? sourceFace : sourceFace.reverse();
        }
        sourceX = sources[0];
        sourceY = sources[1];
        sourceZ = sources[2];
        signX = signs[0];
        signY = signs[1];
        signZ = signs[2];
        inverseX = inverseImages[0];
        inverseY = inverseImages[1];
        inverseZ = inverseImages[2];
        reflects = determinant(x, y, z) < 0;
        int southIndex = rotationIndex(images[Face.S.ordinal()]);
        int eastIndex = rotationIndex(images[Face.E.ordinal()]);
        if (southIndex < 0 || eastIndex < 0) {
            quarterTurnsClockwise = 0;
            reflectsHorizontally = false;
        } else {
            quarterTurnsClockwise = southIndex / 4;
            reflectsHorizontally = eastIndex != Rotation16.rotate(12, quarterTurnsClockwise);
        }
    }

    public static AxisPermutation of(Face imageOfEast, Face imageOfUp, Face imageOfSouth) {
        Objects.requireNonNull(imageOfEast, "imageOfEast");
        Objects.requireNonNull(imageOfUp, "imageOfUp");
        Objects.requireNonNull(imageOfSouth, "imageOfSouth");
        int found = INDEX[key(imageOfEast, imageOfUp, imageOfSouth)];
        if (found < 0) {
            throw new IllegalArgumentException("Axis images must be perpendicular: " + imageOfEast + ", " + imageOfUp + ", " + imageOfSouth);
        }
        return VALUES[found];
    }

    public static AxisPermutation ofIndex(int index) {
        if (index < 0 || index >= COUNT) {
            throw new IllegalArgumentException("Axis permutation index out of range: " + index);
        }
        return VALUES[index];
    }

    public static AxisPermutation between(Frame from, Frame to) {
        return of(betweenImage(Face.E, from, to), betweenImage(Face.U, from, to), betweenImage(Face.S, from, to));
    }

    public static AxisPermutation mirror(Frame plane, QuarterTurn turns) {
        int quarterTurns = turns.coherentFor(plane).getQuarterTurns();
        return of(mirrorImage(Face.E, plane, quarterTurns), mirrorImage(Face.U, plane, quarterTurns),
            mirrorImage(Face.S, plane, quarterTurns));
    }

    public int index() {
        return index;
    }

    public Face x() {
        return x;
    }

    public Face y() {
        return y;
    }

    public Face z() {
        return z;
    }

    public Face face(Face face) {
        return images[face.ordinal()];
    }

    public Axis axis(Axis axis) {
        return switch (axis) {
            case X -> x.getAxis();
            case Y -> y.getAxis();
            case Z -> z.getAxis();
        };
    }

    public boolean reflects() {
        return reflects;
    }

    public boolean reflectsHorizontally() {
        return reflectsHorizontally;
    }

    public boolean flipsWorldUp() {
        return y == Face.D;
    }

    public int quarterTurnsClockwise() {
        return quarterTurnsClockwise;
    }

    public int rotation16(int rotation) {
        int reflected = reflectsHorizontally ? Rotation16.reflect(rotation, Face.E) : rotation;
        return Rotation16.rotate(reflected, quarterTurnsClockwise);
    }

    public AxisPermutation compose(AxisPermutation inner) {
        return of(face(inner.x), face(inner.y), face(inner.z));
    }

    public AxisPermutation inverse() {
        return of(inverseX, inverseY, inverseZ);
    }

    public void vectorInto(double x, double y, double z, double[] out3) {
        double outX = signX * Axis.component(sourceX, x, y, z);
        double outY = signY * Axis.component(sourceY, x, y, z);
        double outZ = signZ * Axis.component(sourceZ, x, y, z);
        out3[0] = outX;
        out3[1] = outY;
        out3[2] = outZ;
    }

    public void cellInto(int x, int y, int z, int[] out3) {
        int outX = cell(signX, Axis.component(sourceX, x, y, z));
        int outY = cell(signY, Axis.component(sourceY, x, y, z));
        int outZ = cell(signZ, Axis.component(sourceZ, x, y, z));
        out3[0] = outX;
        out3[1] = outY;
        out3[2] = outZ;
    }

    @Override
    public String toString() {
        return "AxisPermutation[" + index + ": " + x + "," + y + "," + z + "]";
    }

    private static AxisPermutation[] enumerate() {
        AxisPermutation[] values = new AxisPermutation[COUNT];
        int next = 0;
        for (Face imageX : ORDER) {
            for (Face imageY : ORDER) {
                if (imageY.getAxis() == imageX.getAxis()) {
                    continue;
                }
                for (Face imageZ : ORDER) {
                    if (imageZ.getAxis() == imageX.getAxis() || imageZ.getAxis() == imageY.getAxis()) {
                        continue;
                    }
                    values[next] = new AxisPermutation(next, imageX, imageY, imageZ);
                    next++;
                }
            }
        }
        return values;
    }

    private static int[] indexByImages(AxisPermutation[] values) {
        int[] index = new int[FACE_COUNT * FACE_COUNT * FACE_COUNT];
        Arrays.fill(index, -1);
        for (AxisPermutation value : values) {
            index[key(value.x, value.y, value.z)] = value.index;
        }
        return index;
    }

    private static int key(Face x, Face y, Face z) {
        return (x.ordinal() * FACE_COUNT + y.ordinal()) * FACE_COUNT + z.ordinal();
    }

    private static Face betweenImage(Face face, Frame from, Frame to) {
        int right = dot(face, from.getRight());
        int up = dot(face, from.getUp());
        int normal = dot(face, from.getNormal());
        return faceOf(right * to.getRight().x() + up * to.getUp().x() + normal * to.getNormal().x(),
            right * to.getRight().y() + up * to.getUp().y() + normal * to.getNormal().y(),
            right * to.getRight().z() + up * to.getUp().z() + normal * to.getNormal().z());
    }

    private static Face mirrorImage(Face face, Frame plane, int quarterTurns) {
        int right = dot(face, plane.getRight());
        int up = dot(face, plane.getUp());
        int normal = dot(face, plane.getNormal());
        int rotatedRight = switch (quarterTurns) {
            case 1 -> up;
            case 2 -> -right;
            case 3 -> -up;
            default -> right;
        };
        int rotatedUp = switch (quarterTurns) {
            case 1 -> -right;
            case 2 -> -up;
            case 3 -> right;
            default -> up;
        };
        return faceOf(rotatedRight * plane.getRight().x() + rotatedUp * plane.getUp().x() - normal * plane.getNormal().x(),
            rotatedRight * plane.getRight().y() + rotatedUp * plane.getUp().y() - normal * plane.getNormal().y(),
            rotatedRight * plane.getRight().z() + rotatedUp * plane.getUp().z() - normal * plane.getNormal().z());
    }

    private static Face faceOf(int x, int y, int z) {
        return Face.closest(x, y, z);
    }

    private static int dot(Face a, Face b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    private static int determinant(Face x, Face y, Face z) {
        return x.x() * (y.y() * z.z() - y.z() * z.y())
            - y.x() * (x.y() * z.z() - x.z() * z.y())
            + z.x() * (x.y() * y.z() - x.z() * y.y());
    }

    private static int rotationIndex(Face face) {
        return switch (face) {
            case S -> 0;
            case W -> 4;
            case N -> 8;
            case E -> 12;
            default -> -1;
        };
    }

    private static int cell(double sign, int value) {
        return sign > 0.0D ? value : -value - 1;
    }
}
