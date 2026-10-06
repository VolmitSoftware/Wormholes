package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ProjectionOverlayTest {
    static {
        MinecraftTestBase.bootstrap();
    }

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState GOLD = Blocks.GOLD_BLOCK.defaultBlockState();
    private static final BlockState SIGN = Blocks.OAK_SIGN.defaultBlockState();
    private static final BlockState CHEST = Blocks.CHEST.defaultBlockState();

    @After
    public void deactivate() {
        ProjectionOverlay overlay = ProjectionOverlay.active();
        if (overlay != null) {
            ProjectionOverlay.deactivate(overlay);
        }
    }

    @Test
    public void interceptSubstitutesTheProjectedStateAndRecordsTheShadow() {
        Object level = new Object();
        ProjectionOverlay overlay = new ProjectionOverlay(level);
        long key = CellKeys.pack(5, 70, -3);
        overlay.enter(key, STONE, DIRT, 1, false);
        ProjectionOverlay.activate(overlay);
        BlockPos position = new BlockPos(5, 70, -3);
        assertSame(STONE, ProjectionOverlay.intercept(level, position, GRASS));
        assertSame(GRASS, overlay.get(key).shadow());
        assertSame(STONE, overlay.get(key).projected());
        assertSame(GOLD, ProjectionOverlay.intercept(level, new BlockPos(6, 70, -3), GOLD));
        assertSame(GOLD, ProjectionOverlay.intercept(new Object(), position, GOLD));
        overlay.writing(true);
        assertSame(GOLD, ProjectionOverlay.intercept(level, position, GOLD));
        overlay.writing(false);
        assertSame(GRASS, overlay.get(key).shadow());
        assertEquals(1L, overlay.serverStatesIntercepted());
        assertSame(overlay, ProjectionOverlay.forLevel(level));
        assertNull(ProjectionOverlay.forLevel(new Object()));
    }

    @Test
    public void exitReturnsTheEntryAndDropsTheChunkIndex() {
        ProjectionOverlay overlay = new ProjectionOverlay(new Object());
        long key = CellKeys.pack(17, 64, 33);
        overlay.enter(key, STONE, DIRT, 2, false);
        assertEquals(1, overlay.keysInChunk(1, 2).size());
        ProjectionOverlay.Entry removed = overlay.exit(key);
        assertNotNull(removed);
        assertSame(DIRT, removed.shadow());
        assertEquals(0, overlay.size());
        assertTrue(overlay.keysInChunk(1, 2).isEmpty());
        assertNull(overlay.exit(key));
    }

    @Test
    public void pendingCellsRefreshFromTheFreshChunkAndWriteTheProjection() {
        ProjectionOverlay overlay = new ProjectionOverlay(new Object());
        long pendingKey = CellKeys.pack(32, 70, 48);
        long settledKey = CellKeys.pack(33, 70, 48);
        long elsewhere = CellKeys.pack(200, 70, 48);
        overlay.enter(pendingKey, STONE, null, 1, true);
        overlay.enter(settledKey, STONE, DIRT, 1, false);
        overlay.enter(elsewhere, GOLD, DIRT, 1, false);
        assertEquals(1, overlay.pendingCells());
        Long2ObjectOpenHashMap<BlockState> real = new Long2ObjectOpenHashMap<>();
        real.put(pendingKey, GRASS);
        real.put(settledKey, STONE);
        real.put(elsewhere, DIRT);
        LongArrayList writes = new LongArrayList();
        int touched = overlay.reapply(2, 3, new ProjectionOverlay.ChunkSections() {
            @Override
            public BlockState state(int x, int y, int z) {
                return real.get(CellKeys.pack(x, y, z));
            }

            @Override
            public void write(int x, int y, int z, BlockState state) {
                long key = CellKeys.pack(x, y, z);
                real.put(key, state);
                writes.add(key);
            }

            @Override
            public void blockEntity(int x, int y, int z, BlockEntitySample sample) {
            }
        });
        assertEquals(2, touched);
        assertEquals(0, overlay.pendingCells());
        assertFalse(overlay.get(pendingKey).pending());
        assertSame(GRASS, overlay.get(pendingKey).shadow());
        assertSame(STONE, overlay.get(settledKey).shadow());
        assertEquals(LongArrayList.of(pendingKey), writes);
        assertSame(STONE, real.get(pendingKey));
        assertSame(DIRT, real.get(elsewhere));
    }

    @Test
    public void keysOfFiltersByPortalAndClearReportsEverything() {
        ProjectionOverlay overlay = new ProjectionOverlay(new Object());
        long first = CellKeys.pack(1, 1, 1);
        long second = CellKeys.pack(2, 1, 1);
        long third = CellKeys.pack(3, 1, 1);
        overlay.enter(first, STONE, DIRT, 1, false);
        overlay.enter(second, STONE, DIRT, 2, false);
        overlay.enter(third, STONE, DIRT, 1, true);
        assertEquals(2, overlay.keysOf(1).size());
        assertEquals(1, overlay.keysOf(2).size());
        assertEquals(3, overlay.keys().size());
        overlay.get(second).portalKey(1);
        assertEquals(3, overlay.keysOf(1).size());
        LongArrayList cleared = overlay.clear();
        assertEquals(3, cleared.size());
        assertTrue(overlay.isEmpty());
        assertEquals(0, overlay.pendingCells());
    }

    @Test
    public void reenteringAnExistingCellKeepsTheShadowAndReplacesTheProjection() {
        ProjectionOverlay overlay = new ProjectionOverlay(new Object());
        long key = CellKeys.pack(9, 9, 9);
        overlay.enter(key, STONE, DIRT, 1, false);
        ProjectionOverlay.Entry entry = overlay.enter(key, GOLD, DIRT, 1, false);
        assertSame(GOLD, entry.projected());
        assertSame(DIRT, entry.shadow());
        assertEquals(1, overlay.size());
        assertEquals(1, overlay.keysInChunk(0, 0).size());
    }

    @Test
    public void sectionKeysAppendOnlyTheRequestedSectionAcrossNegativeBoundaries() {
        ProjectionOverlay overlay = new ProjectionOverlay(new Object());
        long below = CellKeys.pack(-1, -1, -1);
        long floor = CellKeys.pack(-16, 0, -16);
        long ceiling = CellKeys.pack(-1, 15, -1);
        long above = CellKeys.pack(-1, 16, -1);
        long adjacent = CellKeys.pack(0, 0, -1);
        for (long key : new long[] {below, floor, ceiling, above, adjacent}) {
            overlay.enter(key, STONE, DIRT, 1, false);
        }
        LongArrayList keys = LongArrayList.of(below);
        overlay.appendSectionKeys(-1, 0, -1, keys);
        assertEquals(3, keys.size());
        assertEquals(new LongOpenHashSet(LongArrayList.of(below, floor, ceiling)), new LongOpenHashSet(keys));
        overlay.appendSectionKeys(-1, 9, -1, keys);
        overlay.appendSectionKeys(9, 0, 9, keys);
        assertEquals(3, keys.size());
        keys.clear();
        overlay.appendSectionKeys(-1, -1, -1, keys);
        assertEquals(LongArrayList.of(below), keys);
        assertEquals(4, overlay.keysInChunk(-1, -1).size());
    }

    @Test
    public void mixedRemovalsAndReentryPreserveSectionMembershipAndPendingCount() {
        ProjectionOverlay overlay = new ProjectionOverlay(new Object());
        long first = CellKeys.pack(1, 0, 1);
        long middle = CellKeys.pack(2, 0, 1);
        long last = CellKeys.pack(3, 0, 1);
        long upper = CellKeys.pack(1, 16, 1);
        for (long key : new long[] {first, middle, last, upper}) {
            overlay.enter(key, STONE, null, 1, true);
        }
        overlay.exit(middle);
        overlay.exit(last);
        overlay.enter(first, GOLD, DIRT, 2, false);
        overlay.enter(middle, GRASS, null, 3, true);
        LongArrayList keys = new LongArrayList();
        overlay.appendSectionKeys(0, 0, 0, keys);
        assertEquals(2, keys.size());
        assertEquals(new LongOpenHashSet(LongArrayList.of(first, middle)), new LongOpenHashSet(keys));
        assertEquals(2, overlay.pendingCells());
        assertEquals(3, overlay.keysInChunk(0, 0).size());
        overlay.exit(first);
        overlay.exit(middle);
        keys.clear();
        overlay.appendSectionKeys(0, 0, 0, keys);
        assertTrue(keys.isEmpty());
        assertEquals(LongArrayList.of(upper), overlay.keysInChunk(0, 0));
        overlay.exit(upper);
        assertTrue(overlay.keysInChunk(0, 0).isEmpty());
        assertEquals(0, overlay.pendingCells());
        overlay.enter(last, STONE, null, 4, true);
        overlay.clear();
        overlay.appendSectionKeys(0, 0, 0, keys);
        assertTrue(keys.isEmpty());
        assertTrue(overlay.keysInChunk(0, 0).isEmpty());
        assertEquals(0, overlay.pendingCells());
    }

    @Test
    public void projectedBlockEntitiesAreRebuiltAfterTheChunkPacketWithTheirSamples() {
        ProjectionOverlay overlay = new ProjectionOverlay(new Object());
        BlockEntitySample sample = new BlockEntitySample("minecraft:sign", new byte[] {10, 0, 0, 0});
        long sign = CellKeys.pack(1, 64, 1);
        long chest = CellKeys.pack(2, 80, 1);
        long stone = CellKeys.pack(3, 64, 1);
        long elsewhere = CellKeys.pack(40, 64, 1);
        overlay.enter(sign, SIGN, DIRT, 1, false).blockEntity(sample);
        overlay.enter(chest, CHEST, null, 1, true);
        overlay.enter(stone, STONE, DIRT, 1, false);
        overlay.enter(elsewhere, CHEST, DIRT, 1, false);
        Long2ObjectOpenHashMap<BlockEntitySample> rebuilt = new Long2ObjectOpenHashMap<>();
        LongArrayList order = new LongArrayList();
        ProjectionOverlay.ChunkSections sections = new ProjectionOverlay.ChunkSections() {
            @Override
            public BlockState state(int x, int y, int z) {
                return DIRT;
            }

            @Override
            public void write(int x, int y, int z, BlockState state) {
            }

            @Override
            public void blockEntity(int x, int y, int z, BlockEntitySample blockEntity) {
                long key = CellKeys.pack(x, y, z);
                order.add(key);
                rebuilt.put(key, blockEntity);
            }
        };
        overlay.reapply(0, 0, sections);

        assertEquals(2, overlay.reapplyBlockEntities(0, 0, sections));

        assertEquals(2, order.size());
        assertSame(sample, rebuilt.get(sign));
        assertTrue(rebuilt.containsKey(chest));
        assertNull(rebuilt.get(chest));
        assertFalse(rebuilt.containsKey(stone));
        assertFalse(rebuilt.containsKey(elsewhere));
    }
}
