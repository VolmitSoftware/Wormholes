package art.arcane.optics.plate;

public record PlateBox(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
    public static final PlateBox EMPTY = new PlateBox(0, 0, 0, 0, 0, 0);

    public PlateBox {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
            throw new IllegalArgumentException("plate box sizes must not be negative");
        }
    }

    public static PlateBox spanning(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            return EMPTY;
        }
        return new PlateBox(minX, minY, minZ, (maxX - minX) + 1, (maxY - minY) + 1, (maxZ - minZ) + 1);
    }

    public long cells() {
        return (long) sizeX * (long) sizeY * (long) sizeZ;
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
