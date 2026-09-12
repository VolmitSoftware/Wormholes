package art.arcane.wormholes.ops.importers;

import art.arcane.wormholes.util.Direction;

/**
 * A portal region reduced to the flat face Wormholes builds on. Region-based plugins store two
 * corners; only a region one block thin on X or Z is a portal aperture.
 */
record RegionFace(int x, int y, int z, Direction facing, int width, int height) {
    /** Null when neither horizontal axis is one block thin. */
    static RegionFace of(int x1, int y1, int z1, int x2, int y2, int z2) {
        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int spanX = Math.abs(x1 - x2) + 1;
        int spanY = Math.abs(y1 - y2) + 1;
        int spanZ = Math.abs(z1 - z2) + 1;
        if (spanX == 1 && spanZ > 1) {
            return new RegionFace(minX, minY, minZ, Direction.E, spanZ, spanY);
        }
        if (spanZ == 1 && spanX > 1) {
            return new RegionFace(minX, minY, minZ, Direction.N, spanX, spanY);
        }
        if (spanX == 1 && spanZ == 1) {
            return new RegionFace(minX, minY, minZ, Direction.N, 1, spanY);
        }
        return null;
    }
}
