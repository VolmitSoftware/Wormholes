package art.arcane.optics.frame;

import art.arcane.optics.math.Angles.Look;

public final class OpticTransform {
    public static final OpticTransform IDENTITY = new OpticTransform(AxisPermutation.IDENTITY, 0.0D, 0.0D, 0.0D);

    private final AxisPermutation permutation;
    private final double translationX;
    private final double translationY;
    private final double translationZ;

    private OpticTransform(AxisPermutation permutation, double translationX, double translationY, double translationZ) {
        this.permutation = permutation;
        this.translationX = translationX;
        this.translationY = translationY;
        this.translationZ = translationZ;
    }

    public static OpticTransform of(AxisPermutation permutation, double tx, double ty, double tz) {
        throw new UnsupportedOperationException();
    }

    public static OpticTransform translation(double tx, double ty, double tz) {
        throw new UnsupportedOperationException();
    }

    public static OpticTransform decode(byte[] bytes) {
        throw new UnsupportedOperationException();
    }

    public AxisPermutation permutation() {
        return permutation;
    }

    public double translationX() {
        return translationX;
    }

    public double translationY() {
        return translationY;
    }

    public double translationZ() {
        return translationZ;
    }

    public boolean reflects() {
        throw new UnsupportedOperationException();
    }

    public boolean flipsWorldUp() {
        throw new UnsupportedOperationException();
    }

    public int quarterTurnsClockwise() {
        throw new UnsupportedOperationException();
    }

    public boolean isIdentity() {
        throw new UnsupportedOperationException();
    }

    public boolean isTranslation() {
        throw new UnsupportedOperationException();
    }

    public double snapTolerance() {
        throw new UnsupportedOperationException();
    }

    public OpticTransform compose(OpticTransform inner) {
        throw new UnsupportedOperationException();
    }

    public OpticTransform inverse() {
        throw new UnsupportedOperationException();
    }

    public void pointInto(double x, double y, double z, double[] out3) {
        throw new UnsupportedOperationException();
    }

    public void snappedPointInto(double x, double y, double z, double[] out3) {
        throw new UnsupportedOperationException();
    }

    public void vectorInto(double x, double y, double z, double[] out3) {
        throw new UnsupportedOperationException();
    }

    public long cell(long cellKey) {
        throw new UnsupportedOperationException();
    }

    public void cellInto(int x, int y, int z, int[] out3) {
        throw new UnsupportedOperationException();
    }

    public int rotation16(int rotation) {
        throw new UnsupportedOperationException();
    }

    public float yaw(float yaw) {
        throw new UnsupportedOperationException();
    }

    public Look look(Look look) {
        throw new UnsupportedOperationException();
    }

    public byte[] encode() {
        throw new UnsupportedOperationException();
    }
}
