package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public final class ProjectionOverlay {
    private static volatile ProjectionOverlay active;

    private final Object level;
    private final Long2ObjectOpenHashMap<Entry> entries;
    private final Long2ObjectOpenHashMap<Int2ObjectOpenHashMap<LongArrayList>> byChunk;
    private boolean writing;
    private int pendingCells;
    private long serverStatesIntercepted;

    public ProjectionOverlay(Object level) {
        this.level = Objects.requireNonNull(level, "level");
        this.entries = new Long2ObjectOpenHashMap<>(4096);
        this.byChunk = new Long2ObjectOpenHashMap<>(64);
    }

    public static ProjectionOverlay active() {
        return active;
    }

    public static void activate(ProjectionOverlay overlay) {
        active = overlay;
    }

    public static void deactivate(ProjectionOverlay overlay) {
        if (active == overlay) {
            active = null;
        }
    }

    public static BlockState intercept(Object level, BlockPos position, BlockState incoming) {
        ProjectionOverlay overlay = active;
        if (overlay == null || overlay.level != level || overlay.writing || overlay.entries.isEmpty()) {
            return incoming;
        }
        return overlay.serverState(ProjectionCellKey.pack(position.getX(), position.getY(), position.getZ()), incoming);
    }

    public static ProjectionOverlay forLevel(Object level) {
        ProjectionOverlay overlay = active;
        return overlay != null && overlay.level == level && !overlay.entries.isEmpty() ? overlay : null;
    }

    public Object level() {
        return level;
    }

    public BlockState serverState(long key, BlockState incoming) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return incoming;
        }
        serverStatesIntercepted++;
        entry.shadow = incoming;
        if (entry.pending) {
            entry.pending = false;
            pendingCells--;
        }
        return entry.projected;
    }

    public Entry enter(long key, BlockState projected, BlockState shadow, int portalKey, boolean pending) {
        Objects.requireNonNull(projected, "projected");
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry(key, portalKey);
            entries.put(key, entry);
            LongArrayList keys = sectionKeys(key);
            entry.sectionIndex = keys.size();
            keys.add(key);
        } else if (entry.pending) {
            pendingCells--;
        }
        entry.portalKey = portalKey;
        entry.projected = projected;
        entry.shadow = shadow;
        entry.pending = pending;
        entry.blockEntity = null;
        if (pending) {
            pendingCells++;
        }
        return entry;
    }

    public Entry get(long key) {
        return entries.get(key);
    }

    public Entry exit(long key) {
        Entry entry = entries.remove(key);
        if (entry == null) {
            return null;
        }
        if (entry.pending) {
            pendingCells--;
        }
        long chunk = chunkKey(key);
        Int2ObjectOpenHashMap<LongArrayList> sections = byChunk.get(chunk);
        int sectionY = ProjectionCellKey.unpackY(key) >> 4;
        LongArrayList keys = sections.get(sectionY);
        long last = keys.removeLong(keys.size() - 1);
        if (last != key) {
            keys.set(entry.sectionIndex, last);
            entries.get(last).sectionIndex = entry.sectionIndex;
        }
        if (keys.isEmpty()) {
            sections.remove(sectionY);
            if (sections.isEmpty()) {
                byChunk.remove(chunk);
            }
        }
        return entry;
    }

    public void writing(boolean value) {
        writing = value;
    }

    public boolean writing() {
        return writing;
    }

    public int size() {
        return entries.size();
    }

    public int pendingCells() {
        return pendingCells;
    }

    public long serverStatesIntercepted() {
        return serverStatesIntercepted;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public LongArrayList keysOf(int portalKey) {
        LongArrayList keys = new LongArrayList();
        ObjectIterator<Long2ObjectMap.Entry<Entry>> iterator = entries.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Long2ObjectMap.Entry<Entry> mapEntry = iterator.next();
            if (mapEntry.getValue().portalKey == portalKey) {
                keys.add(mapEntry.getLongKey());
            }
        }
        return keys;
    }

    public LongArrayList keysInChunk(int chunkX, int chunkZ) {
        LongArrayList keys = new LongArrayList();
        Int2ObjectOpenHashMap<LongArrayList> sections = byChunk.get(ChunkPos.pack(chunkX, chunkZ));
        if (sections != null) {
            for (LongArrayList section : sections.values()) {
                keys.addAll(section);
            }
        }
        return keys;
    }

    public void appendSectionKeys(int sectionX, int sectionY, int sectionZ, LongArrayList out) {
        Int2ObjectOpenHashMap<LongArrayList> sections = byChunk.get(ChunkPos.pack(sectionX, sectionZ));
        if (sections == null) {
            return;
        }
        LongArrayList keys = sections.get(sectionY);
        if (keys != null) {
            out.addAll(keys);
        }
    }

    public int reapply(int chunkX, int chunkZ, ChunkSections sections) {
        Int2ObjectOpenHashMap<LongArrayList> indexed = byChunk.get(ChunkPos.pack(chunkX, chunkZ));
        if (indexed == null) {
            return 0;
        }
        int written = 0;
        for (LongArrayList keys : indexed.values()) {
            written += reapply(keys, sections);
        }
        return written;
    }

    public int reapplyBlockEntities(int chunkX, int chunkZ, ChunkSections sections) {
        Int2ObjectOpenHashMap<LongArrayList> indexed = byChunk.get(ChunkPos.pack(chunkX, chunkZ));
        if (indexed == null) {
            return 0;
        }
        int rebuilt = 0;
        for (LongArrayList keys : indexed.values()) {
            rebuilt += reapplyBlockEntities(keys, sections);
        }
        return rebuilt;
    }

    public LongArrayList keys() {
        LongArrayList keys = new LongArrayList(entries.size());
        ObjectIterator<Long2ObjectMap.Entry<Entry>> iterator = entries.long2ObjectEntrySet().fastIterator();
        while (iterator.hasNext()) {
            keys.add(iterator.next().getLongKey());
        }
        return keys;
    }

    public LongArrayList clear() {
        LongArrayList keys = keys();
        entries.clear();
        byChunk.clear();
        pendingCells = 0;
        return keys;
    }

    private int reapply(LongArrayList keys, ChunkSections sections) {
        int written = 0;
        for (int index = 0; index < keys.size(); index++) {
            long key = keys.getLong(index);
            Entry entry = entries.get(key);
            if (entry == null) {
                continue;
            }
            int x = ProjectionCellKey.unpackX(key);
            int y = ProjectionCellKey.unpackY(key);
            int z = ProjectionCellKey.unpackZ(key);
            BlockState real = sections.state(x, y, z);
            if (real == null) {
                continue;
            }
            entry.shadow = real;
            if (entry.pending) {
                entry.pending = false;
                pendingCells--;
            }
            if (real != entry.projected) {
                sections.write(x, y, z, entry.projected);
            }
            written++;
        }
        return written;
    }

    private int reapplyBlockEntities(LongArrayList keys, ChunkSections sections) {
        int rebuilt = 0;
        for (int index = 0; index < keys.size(); index++) {
            long key = keys.getLong(index);
            Entry entry = entries.get(key);
            if (entry == null || entry.pending || !entry.projected.hasBlockEntity()) {
                continue;
            }
            sections.blockEntity(ProjectionCellKey.unpackX(key), ProjectionCellKey.unpackY(key), ProjectionCellKey.unpackZ(key), entry.blockEntity);
            rebuilt++;
        }
        return rebuilt;
    }

    private LongArrayList sectionKeys(long key) {
        long chunk = chunkKey(key);
        Int2ObjectOpenHashMap<LongArrayList> sections = byChunk.get(chunk);
        if (sections == null) {
            sections = new Int2ObjectOpenHashMap<>(4);
            byChunk.put(chunk, sections);
        }
        int sectionY = ProjectionCellKey.unpackY(key) >> 4;
        LongArrayList keys = sections.get(sectionY);
        if (keys == null) {
            keys = new LongArrayList(64);
            sections.put(sectionY, keys);
        }
        return keys;
    }

    private static long chunkKey(long key) {
        return ChunkPos.pack(ProjectionCellKey.unpackX(key) >> 4, ProjectionCellKey.unpackZ(key) >> 4);
    }

    public static final class Entry {
        private final long key;
        private int portalKey;
        private int sectionIndex;
        private BlockState projected;
        private BlockState shadow;
        private boolean pending;
        private BlockEntitySample blockEntity;

        private Entry(long key, int portalKey) {
            this.key = key;
            this.portalKey = portalKey;
        }

        public long key() {
            return key;
        }

        public int portalKey() {
            return portalKey;
        }

        public BlockState projected() {
            return projected;
        }

        public BlockState shadow() {
            return shadow;
        }

        public boolean pending() {
            return pending;
        }

        public void projected(BlockState state) {
            projected = Objects.requireNonNull(state, "state");
        }

        public void shadow(BlockState state) {
            shadow = state;
        }

        public void portalKey(int value) {
            portalKey = value;
        }

        public BlockEntitySample blockEntity() {
            return blockEntity;
        }

        public void blockEntity(BlockEntitySample sample) {
            blockEntity = sample;
        }
    }

    public interface ChunkSections {
        BlockState state(int x, int y, int z);

        void write(int x, int y, int z, BlockState state);

        void blockEntity(int x, int y, int z, BlockEntitySample sample);
    }
}
