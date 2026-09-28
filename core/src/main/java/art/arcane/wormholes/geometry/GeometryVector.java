package art.arcane.wormholes.geometry;

public record GeometryVector(double x, double y, double z) {
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

    public GeometryVector add(GeometryVector other) {
        return new GeometryVector(x + other.x, y + other.y, z + other.z);
    }

    public GeometryVector subtract(GeometryVector other) {
        return new GeometryVector(x - other.x, y - other.y, z - other.z);
    }

    public GeometryVector multiply(double scalar) {
        return new GeometryVector(x * scalar, y * scalar, z * scalar);
    }

    public GeometryVector normalize() {
        return multiply(1.0D / Math.sqrt(x * x + y * y + z * z));
    }

    public double distance(GeometryVector other) {
        double deltaX = x - other.x;
        double deltaY = y - other.y;
        double deltaZ = z - other.z;
        return Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
    }
}
