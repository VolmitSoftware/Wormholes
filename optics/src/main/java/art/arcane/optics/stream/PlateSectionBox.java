package art.arcane.optics.stream;

import art.arcane.optics.math.BlockBox;

public record PlateSectionBox(int minSectionX, int minSectionY, int minSectionZ, int sizeX, int sizeY, int sizeZ) {
    public static final PlateSectionBox EMPTY = new PlateSectionBox(0, 0, 0, 0, 0, 0);

    public PlateSectionBox {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
            throw new IllegalArgumentException("section box sizes must not be negative");
        }
        if (sizeX > ViewStreamLimits.MAX_SECTION_BOX_EDGE || sizeY > ViewStreamLimits.MAX_SECTION_BOX_EDGE
            || sizeZ > ViewStreamLimits.MAX_SECTION_BOX_EDGE) {
            throw new IllegalArgumentException("section box edge exceeds " + ViewStreamLimits.MAX_SECTION_BOX_EDGE);
        }
        if ((long) sizeX * sizeY * sizeZ > ViewStreamLimits.MAX_BRICKS_PER_PLATE) {
            throw new IllegalArgumentException("section box of " + ((long) sizeX * sizeY * sizeZ) + " bricks exceeds the plate cap");
        }
    }

    public static PlateSectionBox snap(BlockBox cells) {
        if (cells.cells() == 0L) {
            return EMPTY;
        }
        int minX = cells.minX() >> 4;
        int minY = cells.minY() >> 4;
        int minZ = cells.minZ() >> 4;
        int maxX = (cells.minX() + cells.sizeX() - 1) >> 4;
        int maxY = (cells.minY() + cells.sizeY() - 1) >> 4;
        int maxZ = (cells.minZ() + cells.sizeZ() - 1) >> 4;
        return new PlateSectionBox(minX, minY, minZ, (maxX - minX) + 1, (maxY - minY) + 1, (maxZ - minZ) + 1);
    }

    public int brickCount() {
        return sizeX * sizeY * sizeZ;
    }

    public int index(int sectionX, int sectionY, int sectionZ) {
        int dx = sectionX - minSectionX;
        int dy = sectionY - minSectionY;
        int dz = sectionZ - minSectionZ;
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) {
            return -1;
        }
        return ((dx * sizeY) + dy) * sizeZ + dz;
    }

    public int sectionX(int brickIndex) {
        return minSectionX + brickIndex / (sizeY * sizeZ);
    }

    public int sectionY(int brickIndex) {
        return minSectionY + (brickIndex / sizeZ) % sizeY;
    }

    public int sectionZ(int brickIndex) {
        return minSectionZ + brickIndex % sizeZ;
    }

    public boolean contains(int brickIndex) {
        return brickIndex >= 0 && brickIndex < brickCount();
    }
}
