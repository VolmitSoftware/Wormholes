package art.arcane.wormholes.chunk.presend;

public record ClientChunkWindow(int centerX, int centerZ, int chunkRadius) {
    public static final int MINIMUM_CHUNK_RADIUS = 5;
    public static final int VIEW_DISTANCE_MARGIN = 3;

    public ClientChunkWindow {
        if (chunkRadius < 1) {
            throw new IllegalArgumentException("chunkRadius must be positive");
        }
    }

    public static int chunkRadiusFor(int serverViewDistance) {
        return Math.max(serverViewDistance + VIEW_DISTANCE_MARGIN, MINIMUM_CHUNK_RADIUS);
    }

    public static ClientChunkWindow around(int centerX, int centerZ, int serverViewDistance) {
        return new ClientChunkWindow(centerX, centerZ, chunkRadiusFor(serverViewDistance));
    }

    public int viewRange() {
        return chunkRadius * 2 + 1;
    }

    public boolean contains(int x, int z) {
        return Math.abs(x - centerX) <= chunkRadius && Math.abs(z - centerZ) <= chunkRadius;
    }

    public int slotIndex(int x, int z) {
        int range = viewRange();
        return Math.floorMod(z, range) * range + Math.floorMod(x, range);
    }

    public ChunkCoordinate occupantOf(int x, int z) {
        int range = viewRange();
        int lowX = centerX - chunkRadius;
        int lowZ = centerZ - chunkRadius;
        return new ChunkCoordinate(
            lowX + Math.floorMod(x - lowX, range),
            lowZ + Math.floorMod(z - lowZ, range)
        );
    }
}
