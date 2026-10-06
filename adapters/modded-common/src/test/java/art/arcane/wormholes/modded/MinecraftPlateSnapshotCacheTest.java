package art.arcane.wormholes.modded;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import art.arcane.optics.view.WorldChangeTracker;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.Test;

public final class MinecraftPlateSnapshotCacheTest {
    @Test
    public void overlappingViewWorkingSetSurvivesCaptureTraversalAndReopening() {
        WorldChangeTracker changes = new WorldChangeTracker();
        MinecraftPlateSnapshotCache cache = new MinecraftPlateSnapshotCache(changes, MinecraftPlateSnapshotCache.VIEW_LIMITS);
        UUID world = UUID.randomUUID();
        Object chunk = new Object();
        MinecraftPlateCaptureSource.CapturedChunk snapshot = environmentSnapshot();
        for (int column = 0; column < 160; column++) {
            for (int height = 0; height < 16; height++) {
                cache.put(workingSetKey(world, column, height), chunk, 0, snapshot);
            }
        }
        for (int column = 0; column < 160; column++) {
            for (int height = 0; height < 16; height++) {
                assertSame(snapshot, cache.get(workingSetKey(world, column, height), chunk, 3200));
            }
        }
        changes.markChanged(world, 80 << 4, 64, 0);
        assertNull(cache.get(workingSetKey(world, 80, 4), chunk, 3201));
        assertSame(snapshot, cache.get(workingSetKey(world, 81, 4), chunk, 3201));
        assertNull(cache.get(workingSetKey(world, 81, 4), chunk, 12_000));
    }

    @Test
    public void unchangedExactRangeAndOptionsReuseAnImmutableSnapshot() {
        WorldChangeTracker changes = new WorldChangeTracker();
        MinecraftPlateSnapshotCache cache = cache(changes, 8, 4096);
        MinecraftPlateSnapshotCache.Key key = key(UUID.randomUUID(), 64, 79, true, true);
        Object chunk = new Object();
        MinecraftPlateCaptureSource.CapturedChunk snapshot = snapshot();
        cache.put(key, chunk, 10, snapshot);
        assertSame(snapshot, cache.get(key, chunk, 11));
        assertNull(cache.get(key(key.options().worldId(), 80, 95, true, true), chunk, 11));
        assertNull(cache.get(key(key.options().worldId(), 64, 79, false, true), chunk, 11));
        assertNull(cache.get(key(key.options().worldId(), 64, 79, true, false), chunk, 11));
        assertNull(cache.get(key(UUID.randomUUID(), 64, 79, true, true), chunk, 11));
    }

    @Test
    public void blockLightAndBlockEntityColumnChangesInvalidateWithoutDiscardingUnrelatedChunks() {
        WorldChangeTracker changes = new WorldChangeTracker();
        MinecraftPlateSnapshotCache cache = cache(changes, 8, 4096);
        MinecraftPlateSnapshotCache.Key key = key(UUID.randomUUID(), 64, 79, true, true);
        Object chunk = new Object();
        MinecraftPlateCaptureSource.CapturedChunk snapshot = snapshot();
        cache.put(key, chunk, 0, snapshot);
        changes.markChanged(key.options().worldId(), 80, 64, 0);
        assertSame(snapshot, cache.get(key, chunk, 1));
        changes.markChanged(key.options().worldId(), 0, 64, 0);
        assertNull(cache.get(key, chunk, 2));
        cache.put(key, chunk, 3, snapshot);
        changes.markChanged(key.options().worldId(), 0, 0);
        assertNull(cache.get(key, chunk, 4));
    }

    @Test
    public void replacedChunksWorldUnloadAndBackstopNeverReuseOldData() {
        WorldChangeTracker changes = new WorldChangeTracker();
        MinecraftPlateSnapshotCache cache = cache(changes, 8, 4096);
        MinecraftPlateSnapshotCache.Key key = key(UUID.randomUUID(), 64, 79, true, true);
        Object chunk = new Object();
        MinecraftPlateCaptureSource.CapturedChunk snapshot = snapshot();
        cache.put(key, chunk, 0, snapshot);
        assertNull(cache.get(key, new Object(), 1));
        cache.put(key, chunk, 2, snapshot);
        assertSame(snapshot, cache.get(key, chunk, 101));
        assertNull(cache.get(key, chunk, 102));
        cache.put(key, chunk, 103, snapshot);
        cache.clearWorld(key.options().worldId());
        assertNull(cache.get(key, chunk, 104));
    }

    @Test
    public void entryAndByteLimitsEvictOldestSnapshotsAndFlushReleasesEverything() {
        WorldChangeTracker changes = new WorldChangeTracker();
        MinecraftPlateSnapshotCache cache = cache(changes, 8, 1024);
        Object chunk = new Object();
        MinecraftPlateCaptureSource.CapturedChunk snapshot = snapshot();
        MinecraftPlateSnapshotCache.Key first = key(UUID.randomUUID(), 0, 15, true, true);
        MinecraftPlateSnapshotCache.Key second = key(first.options().worldId(), 16, 31, true, true);
        MinecraftPlateSnapshotCache.Key third = key(first.options().worldId(), 32, 47, true, true);
        cache.put(first, chunk, 0, snapshot);
        cache.put(second, chunk, 0, snapshot);
        assertSame(snapshot, cache.get(first, chunk, 1));
        cache.put(third, chunk, 1, snapshot);
        assertNull(cache.get(second, chunk, 1));
        assertSame(snapshot, cache.get(first, chunk, 1));
        cache.clear();
        assertNull(cache.get(first, chunk, 2));
        MinecraftPlateSnapshotCache limited = cache(changes, 1, 4096);
        limited.put(first, chunk, 0, snapshot);
        limited.put(second, chunk, 0, snapshot);
        assertNull(limited.get(first, chunk, 0));
        assertSame(snapshot, limited.get(second, chunk, 0));
    }

    private static MinecraftPlateSnapshotCache cache(WorldChangeTracker changes, int entries, long bytes) {
        return new MinecraftPlateSnapshotCache(changes, new MinecraftPlateSnapshotCache.Limits(entries, 100, bytes));
    }

    private static MinecraftPlateSnapshotCache.Key key(UUID world, int minY, int maxY, boolean blockEntities, boolean environment) {
        return new MinecraftPlateSnapshotCache.Key(0, 0,
            new MinecraftPlateCaptureSource.Options(world, blockEntities, minY, maxY, environment));
    }

    private static MinecraftPlateSnapshotCache.Key workingSetKey(UUID world, int column, int height) {
        return new MinecraftPlateSnapshotCache.Key(column, 0,
            new MinecraftPlateCaptureSource.Options(world, true, height * 16 - 8, height * 16 + 23, true));
    }

    @SuppressWarnings("unchecked")
    private static MinecraftPlateCaptureSource.CapturedChunk environmentSnapshot() {
        PalettedContainer<BlockState>[] sections = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[3];
        String[][] biomes = new String[3][64];
        for (String[] section : biomes) {
            Arrays.fill(section, "minecraft:plains");
        }
        return new MinecraftPlateCaptureSource.CapturedChunk(0, sections, Map.of(), true, null, 0, biomes);
    }

    @SuppressWarnings("unchecked")
    private static MinecraftPlateCaptureSource.CapturedChunk snapshot() {
        PalettedContainer<BlockState>[] sections = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[0];
        return new MinecraftPlateCaptureSource.CapturedChunk(0, sections, Map.of(), true, null, 0, new String[0][]);
    }
}
