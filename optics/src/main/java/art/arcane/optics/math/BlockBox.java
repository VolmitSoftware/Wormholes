package art.arcane.optics.math;

public record BlockBox(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
    public static final BlockBox EMPTY = new BlockBox(0, 0, 0, 0, 0, 0);

    public BlockBox {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
            throw new IllegalArgumentException("block box sizes must not be negative");
        }
    }

    public static BlockBox spanning(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return EMPTY;
        }
        return new BlockBox(minX, minY, minZ, (maxX - minX) + 1, (maxY - minY) + 1, (maxZ - minZ) + 1);
    }

    public int maxX() {
        return minX + sizeX - 1;
    }

    public int maxY() {
        return minY + sizeY - 1;
    }

    public int maxZ() {
        return minZ + sizeZ - 1;
    }

    public long cells() {
        return (long) sizeX * (long) sizeY * (long) sizeZ;
    }

    public boolean isEmpty() {
        return sizeX == 0 || sizeY == 0 || sizeZ == 0;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x < minX + sizeX && y >= minY && y < minY + sizeY && z >= minZ && z < minZ + sizeZ;
    }

    public double centerX() {
        return (minX + maxX() + 1) * 0.5D;
    }

    public double centerY() {
        return (minY + maxY() + 1) * 0.5D;
    }

    public double centerZ() {
        return (minZ + maxZ() + 1) * 0.5D;
    }

    public BlockBox union(BlockBox other) {
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return spanning(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
            Math.max(maxX(), other.maxX()), Math.max(maxY(), other.maxY()), Math.max(maxZ(), other.maxZ()));
    }

    public int index(int x, int y, int z) {
        int dx = x - minX;
        int dy = y - minY;
        int dz = z - minZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) {
            return -1;
        }
        return ((dx * sizeY) + dy) * sizeZ + dz;
    }
}
