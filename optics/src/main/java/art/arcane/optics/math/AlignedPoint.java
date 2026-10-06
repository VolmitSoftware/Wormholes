package art.arcane.optics.math;


public final class AlignedPoint {
    private final double x;
    private final double y;
    private final double z;

    public AlignedPoint(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public AlignedPoint(Vec3d vector) {
        this.x = vector.getX();
        this.y = vector.getY();
        this.z = vector.getZ();
    }


    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public Vec3d toVector() {
        return new Vec3d(x, y, z);
    }
}
