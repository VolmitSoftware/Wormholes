package art.arcane.optics.math;

public record Vec3d(double x, double y, double z) {
    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public int getBlockX() {
        return (int) Math.floor(x);
    }

    public int getBlockY() {
        return (int) Math.floor(y);
    }

    public int getBlockZ() {
        return (int) Math.floor(z);
    }

    public Vec3d add(Vec3d other) {
        return new Vec3d(x + other.x, y + other.y, z + other.z);
    }

    public Vec3d subtract(Vec3d other) {
        return new Vec3d(x - other.x, y - other.y, z - other.z);
    }

    public Vec3d multiply(double scalar) {
        return new Vec3d(x * scalar, y * scalar, z * scalar);
    }

    public double dot(Vec3d other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public Vec3d normalize() {
        return multiply(1.0D / Math.sqrt(x * x + y * y + z * z));
    }

    public double distance(Vec3d other) {
        double deltaX = x - other.x;
        double deltaY = y - other.y;
        double deltaZ = z - other.z;
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }
}
