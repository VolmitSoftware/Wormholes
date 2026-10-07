package art.arcane.optics.scan;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.view.WorldChangeFilter;

public final class ProjectorRemoteFootprint implements WorldChangeFilter {
    public static final int MARGIN_BLOCKS = 2;
    private static final long NO_SECTION = Long.MIN_VALUE;

    private final LongOpenHashSet sections;
    private long lastSection;
    private int minChunkX;
    private int minChunkZ;
    private int maxChunkX;
    private int maxChunkZ;
    private int minSectionY;
    private int maxSectionY;
    private boolean nested;

    public ProjectorRemoteFootprint() {
        this.sections = new LongOpenHashSet(64);
        clear();
    }

    public void record(int x, int y, int z) {
        long section = CellKeys.pack(x >> 4, y >> 4, z >> 4);
        if (section == lastSection) {
            return;
        }
        lastSection = section;
        if (sections.add(section)) {
            include(x >> 4, y >> 4, z >> 4);
        }
    }

    public void markNested() {
        nested = true;
    }

    public boolean nested() {
        return nested;
    }

    public void recordCell(long cellKey) {
        record(CellKeys.unpackX(cellKey), CellKeys.unpackY(cellKey), CellKeys.unpackZ(cellKey));
    }

    public void addAll(ProjectorRemoteFootprint other) {
        LongIterator iterator = other.sections.iterator();
        while (iterator.hasNext()) {
            long section = iterator.nextLong();
            if (sections.add(section)) {
                include(CellKeys.unpackX(section), CellKeys.unpackY(section), CellKeys.unpackZ(section));
            }
        }
        nested = nested || other.nested;
        lastSection = NO_SECTION;
    }

    public void clear() {
        sections.clear();
        nested = false;
        lastSection = NO_SECTION;
        minChunkX = Integer.MAX_VALUE;
        minChunkZ = Integer.MAX_VALUE;
        maxChunkX = Integer.MIN_VALUE;
        maxChunkZ = Integer.MIN_VALUE;
        minSectionY = Integer.MAX_VALUE;
        maxSectionY = Integer.MIN_VALUE;
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    public int size() {
        return sections.size();
    }

    public int queryMinChunkX() {
        return minChunkX - 1;
    }

    public int queryMinChunkZ() {
        return minChunkZ - 1;
    }

    public int queryMaxChunkX() {
        return maxChunkX + 1;
    }

    public int queryMaxChunkZ() {
        return maxChunkZ + 1;
    }

    @Override
    public boolean affectsBlock(int x, int y, int z) {
        int minSectionX = (x - MARGIN_BLOCKS) >> 4;
        int maxSectionX = (x + MARGIN_BLOCKS) >> 4;
        int lowSectionY = (y - MARGIN_BLOCKS) >> 4;
        int highSectionY = (y + MARGIN_BLOCKS) >> 4;
        int minSectionZ = (z - MARGIN_BLOCKS) >> 4;
        int maxSectionZ = (z + MARGIN_BLOCKS) >> 4;
        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            for (int sectionY = lowSectionY; sectionY <= highSectionY; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                    if (sections.contains(CellKeys.pack(sectionX, sectionY, sectionZ))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean affectsColumn(int chunkX, int chunkZ) {
        if (sections.isEmpty()) {
            return false;
        }
        for (int sectionX = chunkX - 1; sectionX <= chunkX + 1; sectionX++) {
            for (int sectionZ = chunkZ - 1; sectionZ <= chunkZ + 1; sectionZ++) {
                if (sectionX < minChunkX || sectionX > maxChunkX || sectionZ < minChunkZ || sectionZ > maxChunkZ) {
                    continue;
                }
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    if (sections.contains(CellKeys.pack(sectionX, sectionY, sectionZ))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void include(int sectionX, int sectionY, int sectionZ) {
        minChunkX = Math.min(minChunkX, sectionX);
        minChunkZ = Math.min(minChunkZ, sectionZ);
        maxChunkX = Math.max(maxChunkX, sectionX);
        maxChunkZ = Math.max(maxChunkZ, sectionZ);
        minSectionY = Math.min(minSectionY, sectionY);
        maxSectionY = Math.max(maxSectionY, sectionY);
    }
}
