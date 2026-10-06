package art.arcane.wormholes.render.view;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.util.Objects;
import art.arcane.optics.view.CachedSection;
import art.arcane.optics.view.SectionCache;

public final class SectionCachedWorldView implements ProjectionWorldView {
    private static final int MAX_WANTED_SECTIONS = 4096;

    private final World world;
    private final ProjectionWorldView live;
    private final SectionCache<BlockData, Material>.WorldSections sections;
    private final ChunkRequests chunkRequests;
    private final Thread owner;
    private final int minHeight;
    private final int maxHeight;
    private final LongOpenHashSet wantedSections;

    public SectionCachedWorldView(World world, ProjectionWorldView live,
                                  SectionCache<BlockData, Material>.WorldSections sections,
                                  ChunkRequests chunkRequests, Thread owner) {
        this.world = Objects.requireNonNull(world);
        this.live = Objects.requireNonNull(live);
        this.sections = Objects.requireNonNull(sections);
        this.chunkRequests = Objects.requireNonNull(chunkRequests);
        this.owner = Objects.requireNonNull(owner);
        this.minHeight = world.getMinHeight();
        this.maxHeight = world.getMaxHeight();
        this.wantedSections = new LongOpenHashSet(16);
    }

    public ProjectionWorldView live() {
        return live;
    }

    public SectionCache<BlockData, Material>.WorldSections sections() {
        return sections;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public int getMinHeight() {
        return minHeight;
    }

    @Override
    public int getMaxHeight() {
        return maxHeight;
    }

    @Override
    public BlockData sampleBlockData(int x, int y, int z) {
        if (!cached()) {
            return live.sampleBlockData(x, y, z);
        }
        if (y < minHeight || y >= maxHeight) {
            return null;
        }
        CachedSection<BlockData, Material> section = sections.section(x >> 4, y >> 4, z >> 4);
        if (section != null) {
            return section.data(CachedSection.index(x & 15, y & 15, z & 15));
        }
        if (!loaded(x, z)) {
            want(x, y, z);
            return null;
        }
        return live.sampleBlockData(x, y, z);
    }

    @Override
    public Material sampleMaterial(int x, int y, int z) {
        if (!cached()) {
            return live.sampleMaterial(x, y, z);
        }
        if (y < minHeight || y >= maxHeight) {
            return null;
        }
        CachedSection<BlockData, Material> section = sections.section(x >> 4, y >> 4, z >> 4);
        if (section != null) {
            return section.material(CachedSection.index(x & 15, y & 15, z & 15));
        }
        if (!loaded(x, z)) {
            want(x, y, z);
            return null;
        }
        return live.sampleMaterial(x, y, z);
    }

    @Override
    public int buriedDepth(int x, int y, int z) {
        if (!cached() || y < minHeight || y >= maxHeight) {
            return -1;
        }
        return sections.buriedDepth(x, y, z);
    }

    @Override
    public String sampleBiome(int x, int y, int z) {
        if (cached() && !loaded(x, z)) {
            return null;
        }
        return live.sampleBiome(x, y, z);
    }

    @Override
    public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        if (cached() && !loaded(x, z)) {
            return null;
        }
        return live.sampleBlockEntity(x, y, z);
    }

    @Override
    public int getLight(int x, int y, int z) {
        if (cached() && !loaded(x, z)) {
            return LIGHT_UNAVAILABLE;
        }
        return live.getLight(x, y, z);
    }

    @Override
    public int getSkyDarken() {
        return live.getSkyDarken();
    }

    @Override
    public boolean isChunkReady(int x, int z) {
        if (!cached()) {
            return true;
        }
        return sections.hasColumn(x >> 4, z >> 4) || world.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public void requestChunk(int x, int z) {
        if (!cached() || world.isChunkLoaded(x >> 4, z >> 4)) {
            return;
        }
        chunkRequests.request(world, x >> 4, z >> 4);
    }

    public void chunkArrived(int chunkX, int chunkZ) {
        if (!cached() || wantedSections.isEmpty()) {
            return;
        }
        LongIterator iterator = wantedSections.iterator();
        while (iterator.hasNext()) {
            long wanted = iterator.nextLong();
            if (CellKeys.unpackX(wanted) == chunkX && CellKeys.unpackZ(wanted) == chunkZ) {
                sections.capture(chunkX, CellKeys.unpackY(wanted), chunkZ);
                iterator.remove();
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SectionCachedWorldView view)) {
            return false;
        }
        return world.equals(view.world);
    }

    @Override
    public int hashCode() {
        return world.hashCode();
    }

    private boolean cached() {
        return Thread.currentThread() == owner && sections.enabled();
    }

    private void want(int x, int y, int z) {
        if (wantedSections.size() >= MAX_WANTED_SECTIONS) {
            wantedSections.clear();
        }
        wantedSections.add(CellKeys.pack(x >> 4, y >> 4, z >> 4));
    }

    private boolean loaded(int x, int z) {
        if (world.isChunkLoaded(x >> 4, z >> 4)) {
            return true;
        }
        chunkRequests.request(world, x >> 4, z >> 4);
        return false;
    }

    @FunctionalInterface
    public interface ChunkRequests {
        void request(World world, int chunkX, int chunkZ);
    }
}
