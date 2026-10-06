package art.arcane.optics.frame;

import java.nio.ByteBuffer;
import java.util.Objects;

import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Angles.Look;
import art.arcane.optics.math.Axis;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.state.TrackShape;

public final class OpticTransform {
    public static final int ENCODED_BYTES = 1 + 3 * Double.BYTES;
    private static final double INTEGRAL_LIMIT = 1 << 30;
    public static final OpticTransform IDENTITY = new OpticTransform(AxisPermutation.IDENTITY, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);

    private final AxisPermutation permutation;
    private final double fromX;
    private final double fromY;
    private final double fromZ;
    private final double toX;
    private final double toY;
    private final double toZ;
    private final int sourceX;
    private final int sourceY;
    private final int sourceZ;
    private final double signX;
    private final double signY;
    private final double signZ;
    private final double snapTolerance;
    private final boolean integral;

    private OpticTransform(AxisPermutation permutation, double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        this.permutation = Objects.requireNonNull(permutation, "permutation");
        this.fromX = fromX;
        this.fromY = fromY;
        this.fromZ = fromZ;
        this.toX = toX;
        this.toY = toY;
        this.toZ = toZ;
        Face[] images = {permutation.x(), permutation.y(), permutation.z()};
        int[] sources = new int[3];
        double[] signs = new double[3];
        for (int source = 0; source < 3; source++) {
            Face image = images[source];
            int target = image.getAxis().ordinal();
            sources[target] = source;
            signs[target] = image.x() + image.y() + image.z();
        }
        sourceX = sources[0];
        sourceY = sources[1];
        sourceZ = sources[2];
        signX = signs[0];
        signY = signs[1];
        signZ = signs[2];
        snapTolerance = coordinateSnapTolerance(fromX, fromY, fromZ, toX, toY, toZ);
        integral = integral(fromX) && integral(fromY) && integral(fromZ) && integral(toX) && integral(toY) && integral(toZ);
    }

    public static OpticTransform of(AxisPermutation permutation, double tx, double ty, double tz) {
        return new OpticTransform(permutation, 0.0D, 0.0D, 0.0D, tx, ty, tz);
    }

    public static OpticTransform translation(double tx, double ty, double tz) {
        return of(AxisPermutation.IDENTITY, tx, ty, tz);
    }

    public static OpticTransform between(Frame from, Vec3d fromOrigin, Frame to, Vec3d toOrigin) {
        return between(from, fromOrigin.x(), fromOrigin.y(), fromOrigin.z(), to, toOrigin.x(), toOrigin.y(), toOrigin.z());
    }

    public static OpticTransform between(Frame from, double fx, double fy, double fz, Frame to, double tx, double ty, double tz) {
        return new OpticTransform(AxisPermutation.between(from, to), fx, fy, fz, tx, ty, tz);
    }

    public static OpticTransform mirror(Frame plane, Vec3d origin, QuarterTurn turns) {
        return new OpticTransform(AxisPermutation.mirror(plane, turns), origin.x(), origin.y(), origin.z(), origin.x(), origin.y(), origin.z());
    }

    public static OpticTransform decode(byte[] bytes) {
        if (bytes == null || bytes.length != ENCODED_BYTES) {
            throw new IllegalArgumentException("Optic transform encoding must be " + ENCODED_BYTES + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        AxisPermutation permutation = AxisPermutation.ofIndex(buffer.get() & 0xFF);
        double tx = buffer.getDouble();
        double ty = buffer.getDouble();
        double tz = buffer.getDouble();
        if (!Double.isFinite(tx) || !Double.isFinite(ty) || !Double.isFinite(tz)) {
            throw new IllegalArgumentException("Optic transform translation must be finite");
        }
        return of(permutation, tx, ty, tz);
    }

    public AxisPermutation permutation() {
        return permutation;
    }

    public double translationX() {
        return toX - signX * component(sourceX, fromX, fromY, fromZ);
    }

    public double translationY() {
        return toY - signY * component(sourceY, fromX, fromY, fromZ);
    }

    public double translationZ() {
        return toZ - signZ * component(sourceZ, fromX, fromY, fromZ);
    }

    public boolean reflects() {
        return permutation.reflects();
    }

    public boolean flipsWorldUp() {
        return permutation.flipsWorldUp();
    }

    public int quarterTurnsClockwise() {
        return permutation.quarterTurnsClockwise();
    }

    public boolean isIdentity() {
        return isTranslation() && translationX() == 0.0D && translationY() == 0.0D && translationZ() == 0.0D;
    }

    public boolean isTranslation() {
        return permutation == AxisPermutation.IDENTITY;
    }

    public double snapTolerance() {
        return snapTolerance;
    }

    public OpticTransform compose(OpticTransform inner) {
        double[] target = new double[3];
        pointInto(inner.toX, inner.toY, inner.toZ, target);
        return new OpticTransform(permutation.compose(inner.permutation), inner.fromX, inner.fromY, inner.fromZ,
            target[0], target[1], target[2]);
    }

    public OpticTransform inverse() {
        return new OpticTransform(permutation.inverse(), toX, toY, toZ, fromX, fromY, fromZ);
    }

    public OpticTransform normalized() {
        return of(permutation, translationX(), translationY(), translationZ());
    }

    public OpticTransform cellAligned() {
        return of(permutation, alignedTranslation(translationX(), signX), alignedTranslation(translationY(), signY),
            alignedTranslation(translationZ(), signZ));
    }

    public Vec3d point(Vec3d point) {
        double[] out = new double[3];
        pointInto(point.x(), point.y(), point.z(), out);
        return new Vec3d(out[0], out[1], out[2]);
    }

    public void pointInto(double x, double y, double z, double[] out3) {
        double offsetX = x - fromX;
        double offsetY = y - fromY;
        double offsetZ = z - fromZ;
        double outX = toX + signX * component(sourceX, offsetX, offsetY, offsetZ);
        double outY = toY + signY * component(sourceY, offsetX, offsetY, offsetZ);
        double outZ = toZ + signZ * component(sourceZ, offsetX, offsetY, offsetZ);
        out3[0] = outX;
        out3[1] = outY;
        out3[2] = outZ;
    }

    public void snappedPointInto(double x, double y, double z, double[] out3) {
        pointInto(x, y, z, out3);
        out3[0] = snapNearInteger(out3[0], snapTolerance);
        out3[1] = snapNearInteger(out3[1], snapTolerance);
        out3[2] = snapNearInteger(out3[2], snapTolerance);
    }

    public Vec3d vector(Vec3d vector) {
        double[] out = new double[3];
        vectorInto(vector.x(), vector.y(), vector.z(), out);
        return new Vec3d(out[0], out[1], out[2]);
    }

    public void vectorInto(double x, double y, double z, double[] out3) {
        permutation.vectorInto(x, y, z, out3);
    }

    public Face face(Face face) {
        return permutation.face(face);
    }

    public Axis axis(Axis axis) {
        return permutation.axis(axis);
    }

    public long cell(long cellKey) {
        int x = CellKeys.unpackX(cellKey);
        int y = CellKeys.unpackY(cellKey);
        int z = CellKeys.unpackZ(cellKey);
        if (integral) {
            int offsetX = x - (int) fromX;
            int offsetY = y - (int) fromY;
            int offsetZ = z - (int) fromZ;
            return CellKeys.pack(integralCell(toX, signX, sourceX, offsetX, offsetY, offsetZ),
                integralCell(toY, signY, sourceY, offsetX, offsetY, offsetZ),
                integralCell(toZ, signZ, sourceZ, offsetX, offsetY, offsetZ));
        }
        double offsetX = x + 0.5D - fromX;
        double offsetY = y + 0.5D - fromY;
        double offsetZ = z + 0.5D - fromZ;
        return CellKeys.pack(snappedCell(toX, signX, sourceX, offsetX, offsetY, offsetZ),
            snappedCell(toY, signY, sourceY, offsetX, offsetY, offsetZ),
            snappedCell(toZ, signZ, sourceZ, offsetX, offsetY, offsetZ));
    }

    public void cellInto(int x, int y, int z, int[] out3) {
        if (integral) {
            int offsetX = x - (int) fromX;
            int offsetY = y - (int) fromY;
            int offsetZ = z - (int) fromZ;
            int outX = integralCell(toX, signX, sourceX, offsetX, offsetY, offsetZ);
            int outY = integralCell(toY, signY, sourceY, offsetX, offsetY, offsetZ);
            int outZ = integralCell(toZ, signZ, sourceZ, offsetX, offsetY, offsetZ);
            out3[0] = outX;
            out3[1] = outY;
            out3[2] = outZ;
            return;
        }
        double offsetX = x + 0.5D - fromX;
        double offsetY = y + 0.5D - fromY;
        double offsetZ = z + 0.5D - fromZ;
        int outX = snappedCell(toX, signX, sourceX, offsetX, offsetY, offsetZ);
        int outY = snappedCell(toY, signY, sourceY, offsetX, offsetY, offsetZ);
        int outZ = snappedCell(toZ, signZ, sourceZ, offsetX, offsetY, offsetZ);
        out3[0] = outX;
        out3[1] = outY;
        out3[2] = outZ;
    }

    public BlockBox box(BlockBox box, int margin) {
        if (box.cells() == 0L) {
            return BlockBox.EMPTY;
        }
        double minX = box.minX() + 0.5D - fromX;
        double minY = box.minY() + 0.5D - fromY;
        double minZ = box.minZ() + 0.5D - fromZ;
        double maxX = (box.minX() + box.sizeX() - 1) + 0.5D - fromX;
        double maxY = (box.minY() + box.sizeY() - 1) + 0.5D - fromY;
        double maxZ = (box.minZ() + box.sizeZ() - 1) + 0.5D - fromZ;
        double firstX = snapped(toX, signX, sourceX, minX, minY, minZ);
        double firstY = snapped(toY, signY, sourceY, minX, minY, minZ);
        double firstZ = snapped(toZ, signZ, sourceZ, minX, minY, minZ);
        double lastX = snapped(toX, signX, sourceX, maxX, maxY, maxZ);
        double lastY = snapped(toY, signY, sourceY, maxX, maxY, maxZ);
        double lastZ = snapped(toZ, signZ, sourceZ, maxX, maxY, maxZ);
        return BlockBox.spanning(
            (int) Math.floor(Math.min(firstX, lastX)) - margin,
            (int) Math.floor(Math.min(firstY, lastY)) - margin,
            (int) Math.floor(Math.min(firstZ, lastZ)) - margin,
            (int) Math.floor(Math.max(firstX, lastX)) + margin,
            (int) Math.floor(Math.max(firstY, lastY)) + margin,
            (int) Math.floor(Math.max(firstZ, lastZ)) + margin);
    }

    public Box box(Box box) {
        double[] min = new double[3];
        double[] max = new double[3];
        pointInto(box.getXa(), box.getYa(), box.getZa(), min);
        pointInto(box.getXb(), box.getYb(), box.getZb(), max);
        return new Box(min[0], max[0], min[1], max[1], min[2], max[2]);
    }

    public Frame frame(Frame frame) {
        return Frame.fromNormalUp(face(frame.getNormal()), face(frame.getUp()));
    }

    public int rotation16(int rotation) {
        return permutation.rotation16(rotation);
    }

    public TrackShape trackShape(TrackShape shape) {
        return shape.map(permutation);
    }

    public float yaw(float yaw) {
        double radians = Math.toRadians(yaw);
        double x = -Math.sin(radians);
        double z = Math.cos(radians);
        return Angles.yaw(signX * component(sourceX, x, 0.0D, z), signZ * component(sourceZ, x, 0.0D, z));
    }

    public Look look(Look look) {
        double yawRadians = Math.toRadians(look.yaw());
        double pitchRadians = Math.toRadians(look.pitch());
        double horizontal = Math.cos(pitchRadians);
        double x = -horizontal * Math.sin(yawRadians);
        double y = -Math.sin(pitchRadians);
        double z = horizontal * Math.cos(yawRadians);
        return Angles.look(signX * component(sourceX, x, y, z), signY * component(sourceY, x, y, z),
            signZ * component(sourceZ, x, y, z));
    }

    public byte[] encode() {
        return ByteBuffer.allocate(ENCODED_BYTES)
            .put((byte) permutation.index())
            .putDouble(translationX())
            .putDouble(translationY())
            .putDouble(translationZ())
            .array();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OpticTransform transform
            && permutation == transform.permutation
            && Double.compare(fromX, transform.fromX) == 0
            && Double.compare(fromY, transform.fromY) == 0
            && Double.compare(fromZ, transform.fromZ) == 0
            && Double.compare(toX, transform.toX) == 0
            && Double.compare(toY, transform.toY) == 0
            && Double.compare(toZ, transform.toZ) == 0;
    }

    @Override
    public int hashCode() {
        int hash = permutation.index();
        hash = 31 * hash + Double.hashCode(fromX);
        hash = 31 * hash + Double.hashCode(fromY);
        hash = 31 * hash + Double.hashCode(fromZ);
        hash = 31 * hash + Double.hashCode(toX);
        hash = 31 * hash + Double.hashCode(toY);
        return 31 * hash + Double.hashCode(toZ);
    }

    @Override
    public String toString() {
        return "OpticTransform[" + permutation + " from (" + fromX + ", " + fromY + ", " + fromZ + ") to ("
            + toX + ", " + toY + ", " + toZ + ")]";
    }

    private double snapped(double target, double sign, int source, double x, double y, double z) {
        return snapNearInteger(target + sign * component(source, x, y, z), snapTolerance);
    }

    private static int integralCell(double target, double sign, int source, int x, int y, int z) {
        int offset = source == 0 ? x : source == 1 ? y : z;
        return (int) target + (sign > 0.0D ? offset : -offset - 1);
    }

    private int snappedCell(double target, double sign, int source, double x, double y, double z) {
        return (int) Math.floor(snapped(target, sign, source, x, y, z));
    }

    private static double component(int axis, double x, double y, double z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static boolean integral(double value) {
        return value == Math.rint(value) && Math.abs(value) <= INTEGRAL_LIMIT;
    }

    private static double alignedTranslation(double translation, double sign) {
        return sign > 0.0D ? Math.ceil(translation - 0.5D) : Math.floor(translation + 0.5D);
    }

    private static double coordinateSnapTolerance(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double largestUlp = Math.max(Math.ulp(fromX), Math.ulp(fromY));
        largestUlp = Math.max(largestUlp, Math.ulp(fromZ));
        largestUlp = Math.max(largestUlp, Math.ulp(toX));
        largestUlp = Math.max(largestUlp, Math.ulp(toY));
        largestUlp = Math.max(largestUlp, Math.ulp(toZ));
        return Math.max(1.0E-10D, largestUlp * 8.0D);
    }

    private static double snapNearInteger(double value, double tolerance) {
        double nearestInteger = Math.rint(value);
        return Math.abs(value - nearestInteger) <= tolerance ? nearestInteger : value;
    }
}
