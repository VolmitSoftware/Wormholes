package art.arcane.optics.view;

import art.arcane.optics.math.CellKeys;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class SectionCache<B, M> {
    private static final int RECENT_SLOTS = 256;
    private static final int RECENT_MASK = RECENT_SLOTS - 1;

    private final CachedSection.Builder<B, M> builder;
    private final List<WorldSections> worlds;
    private final long[][] scratchNeighbours;
    private Limits limits;
    private long bytes;
    private int tick;
    private int columnsOpened;

    public SectionCache(BlockStates<B, M> blocks, Limits limits) {
        this.builder = new CachedSection.Builder<B, M>(Objects.requireNonNull(blocks));
        this.worlds = new ArrayList<WorldSections>(4);
        this.scratchNeighbours = new long[CachedSection.NEIGHBOURS][];
        this.limits = Objects.requireNonNull(limits);
        this.bytes = 0L;
        this.tick = 0;
        this.columnsOpened = 0;
    }

    public WorldSections world(Source<B, M> source, int minSectionY, int maxSectionY) {
        WorldSections sections = new WorldSections(source, minSectionY, maxSectionY);
        worlds.add(sections);
        return sections;
    }

    public void tick(int currentTick) {
        tick = currentTick;
        columnsOpened = 0;
        for (WorldSections sections : worlds) {
            sections.beginTick();
        }
    }

    public void configure(Limits configured) {
        limits = Objects.requireNonNull(configured);
        if (!configured.enabled()) {
            clear();
            return;
        }
        if (bytes > configured.maxBytes()) {
            evictOldest();
        }
    }

    public long bytes() {
        return bytes;
    }

    public int sectionCount() {
        int count = 0;
        for (WorldSections sections : worlds) {
            count += sections.sections.size();
        }
        return count;
    }

    public void clear() {
        for (WorldSections sections : worlds) {
            sections.clear();
        }
    }

    public void release(WorldSections sections) {
        sections.clear();
        worlds.remove(sections);
    }

    private void evictOldest() {
        long target = limits.maxBytes() - (limits.maxBytes() / 10L);
        List<Victim> victims = new ArrayList<Victim>(sectionCount());
        for (WorldSections sections : worlds) {
            for (CachedSection<B, M> section : sections.sections.values()) {
                victims.add(new Victim(sections, section));
            }
        }
        victims.sort(Comparator.comparingInt(Victim::lastAccessTick));
        for (Victim victim : victims) {
            if (bytes <= target || victim.lastAccessTick() == tick) {
                return;
            }
            victim.sections.remove(victim.section);
        }
    }

    public record Limits(boolean enabled, long maxBytes, int chunksPerTick, int ttlTicks) {
        public Limits {
            maxBytes = Math.max(1L, maxBytes);
            chunksPerTick = Math.max(0, chunksPerTick);
            ttlTicks = Math.max(1, ttlTicks);
        }

        public static Limits from(boolean enabled, int maxMb, int chunksPerTick, int ttlTicks) {
            return new Limits(enabled, ((long) Math.clamp(maxMb, 1, 4096)) << 20,
                Math.clamp(chunksPerTick, 1, 1024), Math.clamp(ttlTicks, 20, 72_000));
        }
    }

    public interface Source<B, M> {
        boolean columnAvailable(int chunkX, int chunkZ);

        boolean capture(int sectionX, int sectionY, int sectionZ, CachedSection.Builder<B, M> builder);

        void discardColumn(int chunkX, int chunkZ);

        void endTick();
    }

    private final class Victim {
        private final WorldSections sections;
        private final CachedSection<B, M> section;

        private Victim(WorldSections sections, CachedSection<B, M> section) {
            this.sections = sections;
            this.section = section;
        }

        private int lastAccessTick() {
            return section.lastAccessTick();
        }
    }

    public final class WorldSections {
        private final Source<B, M> source;
        private final int minSectionY;
        private final int maxSectionY;
        private final Long2ObjectOpenHashMap<CachedSection<B, M>> sections;
        private final Long2IntOpenHashMap columns;
        private final LongOpenHashSet openedColumns;
        private final CachedSection<B, M>[] recent;

        @SuppressWarnings("unchecked")
        private WorldSections(Source<B, M> source, int minSectionY, int maxSectionY) {
            this.source = Objects.requireNonNull(source);
            this.minSectionY = minSectionY;
            this.maxSectionY = maxSectionY;
            this.sections = new Long2ObjectOpenHashMap<CachedSection<B, M>>(1024);
            this.columns = new Long2IntOpenHashMap(256);
            this.openedColumns = new LongOpenHashSet(64);
            this.recent = (CachedSection<B, M>[]) new CachedSection<?, ?>[RECENT_SLOTS];
        }

        public boolean enabled() {
            return limits.enabled();
        }

        public CachedSection<B, M> section(int sectionX, int sectionY, int sectionZ) {
            int slot = slot(sectionX, sectionY, sectionZ);
            CachedSection<B, M> hit = recent[slot];
            if (hit != null && hit.is(sectionX, sectionY, sectionZ)) {
                return hit;
            }
            return lookup(sectionX, sectionY, sectionZ, slot);
        }

        public int buriedDepth(int x, int y, int z) {
            CachedSection<B, M> section = section(x >> 4, y >> 4, z >> 4);
            if (section == null) {
                return -1;
            }
            if (!section.hasBuriedDepth() && !computeBuriedDepth(section)) {
                return -1;
            }
            return section.buriedDepth(CachedSection.index(x & 15, y & 15, z & 15));
        }

        public void capture(int sectionX, int sectionY, int sectionZ) {
            if (!limits.enabled() || sectionY < minSectionY || sectionY > maxSectionY) {
                return;
            }
            long column = CellKeys.chunkKey(sectionX, sectionZ);
            if (!openedColumns.contains(column)) {
                if (!source.columnAvailable(sectionX, sectionZ)) {
                    return;
                }
                columnsOpened++;
                openedColumns.add(column);
            }
            long key = CellKeys.sectionKey(sectionX, sectionY, sectionZ);
            builder.reset();
            if (!source.capture(sectionX, sectionY, sectionZ, builder)) {
                return;
            }
            install(key, builder.build(sectionX, sectionY, sectionZ, tick), sections.get(key));
            recent[slot(sectionX, sectionY, sectionZ)] = null;
        }

        public boolean hasColumn(int chunkX, int chunkZ) {
            return columns.containsKey(CellKeys.chunkKey(chunkX, chunkZ));
        }

        public int size() {
            return sections.size();
        }

        public void blockChanged(int x, int y, int z) {
            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            source.discardColumn(chunkX, chunkZ);
            CachedSection<B, M> section = sections.get(CellKeys.sectionKey(chunkX, y >> 4, chunkZ));
            if (section != null) {
                remove(section);
            }
        }

        public void columnChanged(int chunkX, int chunkZ) {
            source.discardColumn(chunkX, chunkZ);
            if (!columns.containsKey(CellKeys.chunkKey(chunkX, chunkZ))) {
                return;
            }
            for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                CachedSection<B, M> section = sections.get(CellKeys.sectionKey(chunkX, sectionY, chunkZ));
                if (section != null) {
                    remove(section);
                }
            }
        }

        public void clear() {
            for (CachedSection<B, M> section : sections.values()) {
                bytes -= section.bytes();
            }
            sections.clear();
            columns.clear();
            openedColumns.clear();
            Arrays.fill(recent, null);
        }

        private void beginTick() {
            openedColumns.clear();
            Arrays.fill(recent, null);
            source.endTick();
        }

        private CachedSection<B, M> lookup(int sectionX, int sectionY, int sectionZ, int slot) {
            if (!limits.enabled() || sectionY < minSectionY || sectionY > maxSectionY) {
                return null;
            }
            long key = CellKeys.sectionKey(sectionX, sectionY, sectionZ);
            CachedSection<B, M> cached = sections.get(key);
            if (cached != null && tick - cached.filledTick() < limits.ttlTicks()) {
                cached.touch(tick);
                recent[slot] = cached;
                return cached;
            }
            CachedSection<B, M> filled = fill(sectionX, sectionY, sectionZ, key, cached);
            CachedSection<B, M> result = filled != null ? filled : cached;
            if (result != null) {
                result.touch(tick);
                recent[slot] = result;
            }
            return result;
        }

        private CachedSection<B, M> fill(int sectionX, int sectionY, int sectionZ, long key, CachedSection<B, M> previous) {
            long column = CellKeys.chunkKey(sectionX, sectionZ);
            if (!openedColumns.contains(column)) {
                if (columnsOpened >= limits.chunksPerTick() || !source.columnAvailable(sectionX, sectionZ)) {
                    return null;
                }
                columnsOpened++;
                openedColumns.add(column);
            }
            builder.reset();
            if (!source.capture(sectionX, sectionY, sectionZ, builder)) {
                return null;
            }
            CachedSection<B, M> section = builder.build(sectionX, sectionY, sectionZ, tick);
            install(key, section, previous);
            return section;
        }

        private void install(long key, CachedSection<B, M> section, CachedSection<B, M> previous) {
            if (previous != null) {
                bytes -= previous.bytes();
                if (section.sameOpacity(previous)) {
                    section.adoptBuriedDepth(previous);
                } else {
                    clearNeighbourBuriedDepth(section.sectionX(), section.sectionY(), section.sectionZ());
                }
            } else {
                columns.addTo(CellKeys.chunkKey(section.sectionX(), section.sectionZ()), 1);
                clearPartialNeighbourBuriedDepth(section.sectionX(), section.sectionY(), section.sectionZ());
            }
            sections.put(key, section);
            bytes += section.bytes();
            if (bytes > limits.maxBytes()) {
                evictOldest();
            }
        }

        private void remove(CachedSection<B, M> removed) {
            int sectionX = removed.sectionX();
            int sectionY = removed.sectionY();
            int sectionZ = removed.sectionZ();
            long key = CellKeys.sectionKey(sectionX, sectionY, sectionZ);
            if (sections.get(key) != removed) {
                return;
            }
            sections.remove(key);
            bytes -= removed.bytes();
            long column = CellKeys.chunkKey(sectionX, sectionZ);
            if (columns.addTo(column, -1) <= 1) {
                columns.remove(column);
            }
            int slot = slot(sectionX, sectionY, sectionZ);
            if (recent[slot] == removed) {
                recent[slot] = null;
            }
            clearNeighbourBuriedDepth(sectionX, sectionY, sectionZ);
        }

        private void clearPartialNeighbourBuriedDepth(int sectionX, int sectionY, int sectionZ) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!CachedSection.haloNeighbour(dx, dy, dz)) {
                            continue;
                        }
                        CachedSection<B, M> neighbour = sections.get(CellKeys.sectionKey(sectionX + dx, sectionY + dy, sectionZ + dz));
                        if (neighbour != null && neighbour.partialBuriedDepth()) {
                            neighbour.clearBuriedDepth();
                        }
                    }
                }
            }
        }

        private void clearNeighbourBuriedDepth(int sectionX, int sectionY, int sectionZ) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!CachedSection.haloNeighbour(dx, dy, dz)) {
                            continue;
                        }
                        CachedSection<B, M> neighbour = sections.get(CellKeys.sectionKey(sectionX + dx, sectionY + dy, sectionZ + dz));
                        if (neighbour != null) {
                            neighbour.clearBuriedDepth();
                        }
                    }
                }
            }
        }

        private boolean computeBuriedDepth(CachedSection<B, M> center) {
            long[][] neighbours = scratchNeighbours;
            Arrays.fill(neighbours, null);
            int sectionX = center.sectionX();
            int sectionY = center.sectionY();
            int sectionZ = center.sectionZ();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (!CachedSection.haloNeighbour(dx, dy, dz)) {
                            continue;
                        }
                        int neighbourY = sectionY + dy;
                        if (neighbourY < minSectionY || neighbourY > maxSectionY) {
                            neighbours[CachedSection.neighbour(dx, dy, dz)] = CachedSection.OPEN;
                            continue;
                        }
                        CachedSection<B, M> neighbour = section(sectionX + dx, neighbourY, sectionZ + dz);
                        neighbours[CachedSection.neighbour(dx, dy, dz)] = neighbour == null ? null : neighbour.occludingBits();
                    }
                }
            }
            if (sections.get(CellKeys.sectionKey(sectionX, sectionY, sectionZ)) != center) {
                return false;
            }
            neighbours[CachedSection.neighbour(0, 0, 0)] = center.occludingBits();
            center.computeBuriedDepth(neighbours);
            Arrays.fill(neighbours, null);
            return true;
        }

        private static int slot(int sectionX, int sectionY, int sectionZ) {
            return ((sectionX * 73) ^ (sectionY * 19) ^ (sectionZ * 151)) & RECENT_MASK;
        }
    }
}
