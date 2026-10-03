package art.arcane.wormholes.modded;

import art.arcane.wormholes.render.ProjectionWorldChangeTracker;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

final class MinecraftPlateSnapshotCache {
    static final Limits VIEW_LIMITS = new Limits(16_384, 12_000, 268_435_456L);

    private final ProjectionWorldChangeTracker changes;
    private final Limits limits;
    private final LinkedHashMap<Key, Entry> snapshots = new LinkedHashMap<>(16, 0.75F, true);
    private long bytes;
    private long hits;
    private long misses;
    private long invalidations;
    private long expirations;
    private long captures;
    private long evictions;
    private long dirtyInvalidations;
    private long chunkInvalidations;

    MinecraftPlateSnapshotCache(ProjectionWorldChangeTracker changes, Limits limits) {
        this.changes = Objects.requireNonNull(changes);
        this.limits = Objects.requireNonNull(limits);
    }

    MinecraftPlateCaptureSource.CapturedChunk get(Key key, Object chunkIdentity, long tick) {
        Entry entry = snapshots.get(key);
        if (entry == null) {
            misses++;
            return null;
        }
        boolean changedChunk = chunkIdentity == null || entry.chunkIdentity().get() != chunkIdentity;
        boolean expired = tick - entry.tick() >= limits.lifetimeTicks();
        boolean dirty = changes.dirtySince(key.options().worldId(), key.chunkX(), key.chunkZ(), key.chunkX(), key.chunkZ(), entry.version());
        if (changedChunk || expired || dirty) {
            invalidations++;
            if (expired) {
                expirations++;
            }
            if (changedChunk) {
                chunkInvalidations++;
            }
            if (dirty) {
                dirtyInvalidations++;
            }
            bytes -= snapshots.remove(key).bytes();
            return null;
        }
        hits++;
        return entry.snapshot();
    }

    void put(Key key, Object chunkIdentity, long tick, MinecraftPlateCaptureSource.CapturedChunk snapshot) {
        captures++;
        long size = bytes(Objects.requireNonNull(snapshot));
        if (size > limits.maximumBytes()) {
            Entry removed = snapshots.remove(key);
            if (removed != null) {
                bytes -= removed.bytes();
            }
            return;
        }
        Entry previous = snapshots.put(key, new Entry(new WeakReference<>(Objects.requireNonNull(chunkIdentity)), tick, changes.currentVersion(), snapshot, size));
        bytes += size - (previous == null ? 0 : previous.bytes());
        while (snapshots.size() > limits.maximumEntries() || bytes > limits.maximumBytes()) {
            bytes -= snapshots.pollFirstEntry().getValue().bytes();
            evictions++;
        }
    }

    void clear() {
        snapshots.clear();
        bytes = 0;
    }

    void clearWorld(UUID worldId) {
        Iterator<Map.Entry<Key, Entry>> entries = snapshots.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, Entry> entry = entries.next();
            if (entry.getKey().options().worldId().equals(worldId)) {
                bytes -= entry.getValue().bytes();
                entries.remove();
            }
        }
    }

    private static long bytes(MinecraftPlateCaptureSource.CapturedChunk snapshot) {
        long size = 512L + snapshot.sections().length * 512L;
        for (PalettedContainer<BlockState> section : snapshot.sections()) {
            if (section != null) {
                size += 65_536L;
            }
        }
        if (snapshot.light() != null) {
            size += Math.max(snapshot.sections().length, snapshot.biomes().length) * 8192L;
        }
        for (String[] section : snapshot.biomes()) {
            size += 512L;
            for (String biome : section) {
                size += biome == null ? 0 : 40L + biome.length() * 2L;
            }
        }
        for (BlockEntitySample sample : snapshot.blockEntities().values()) {
            size += 128L + sample.bytes() * 2L;
        }
        return size;
    }

    record Limits(int maximumEntries, int lifetimeTicks, long maximumBytes) {
        Limits {
            if (maximumEntries < 1 || lifetimeTicks < 1 || maximumBytes < 1) {
                throw new IllegalArgumentException("Plate snapshot cache limits");
            }
        }
    }

    record Key(int chunkX, int chunkZ, MinecraftPlateCaptureSource.Options options) {
    }

    private record Entry(WeakReference<Object> chunkIdentity, long tick, long version, MinecraftPlateCaptureSource.CapturedChunk snapshot, long bytes) {
    }
}
