package art.arcane.wormholes.chunk.presend;

import java.util.Comparator;

public record ChunkCoordinate(int x, int z) {
    public int chebyshevDistance(int fromX, int fromZ) {
        return Math.max(Math.abs(x - fromX), Math.abs(z - fromZ));
    }

    public static Comparator<ChunkCoordinate> nearestFirst(int centerX, int centerZ) {
        return Comparator
            .comparingInt((ChunkCoordinate coordinate) -> coordinate.chebyshevDistance(centerX, centerZ))
            .thenComparingInt(ChunkCoordinate::x)
            .thenComparingInt(ChunkCoordinate::z);
    }
}
