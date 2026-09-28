package art.arcane.wormholes.network.replication.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.replication.BlockChange;

public final class ChunkDirtySetOcclusionMergeTest {
    @Test
    public void staleSnapshotCannotReplaceNewerQueuedCenterState() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        int packed = BlockChange.pack(3, 64, 7);
        long capturedRevision = set.liveBlockRevision();
        set.advanceLiveBlockRevision();
        set.putBlock(packed, "minecraft:gold_block", BlockChange.FLAG_NONE);

        ChunkDirtySet.BlockPutResult result = set.putSnapshotBlockIfBelowCapacity(
            packed, "minecraft:dirt", BlockChange.FLAG_NONE, 8, capturedRevision);
        BlockChange change = set.drainAll().blocks().get(0);

        assertEquals(ChunkDirtySet.BlockPutResult.STALE_REVISION, result);
        assertEquals("minecraft:gold_block", change.state());
    }

    @Test
    public void staleSnapshotCannotClearNewerNeighborOcclusionFlag() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        int packed = BlockChange.pack(4, 64, 7);
        long capturedRevision = set.liveBlockRevision();
        set.advanceLiveBlockRevision();
        set.putBlock(packed, "minecraft:stone", BlockChange.FLAG_OCCLUDED);

        ChunkDirtySet.BlockPutResult result = set.putSnapshotBlockOcclusionIfBelowCapacity(
            packed, "minecraft:stone", BlockChange.FLAG_NONE, 8, capturedRevision);
        BlockChange change = set.drainAll().blocks().get(0);

        assertEquals(ChunkDirtySet.BlockPutResult.STALE_REVISION, result);
        assertEquals(BlockChange.FLAG_OCCLUDED, change.flags());
    }

    @Test
    public void occlusionRefreshPreservesQueuedStateAndIndependentFlags() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        int packed = BlockChange.pack(3, 64, 7);
        set.putBlock(packed, "minecraft:chest",
            (byte) (BlockChange.FLAG_BLOCK_ENTITY_FOLLOWS | BlockChange.FLAG_OCCLUDED));

        ChunkDirtySet.BlockPutResult result = set.putBlockOcclusionIfBelowCapacity(
            packed, "minecraft:barrel", BlockChange.FLAG_NONE, 8);
        BlockChange change = set.drainAll().blocks().get(0);

        assertEquals(ChunkDirtySet.BlockPutResult.UPDATED, result);
        assertEquals("minecraft:chest", change.state());
        assertEquals(BlockChange.FLAG_BLOCK_ENTITY_FOLLOWS, change.flags());
    }

    @Test
    public void occlusionRefreshCanSetTheOccludedBitOnAnExistingChange() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        int packed = BlockChange.pack(3, 64, 7);
        set.putBlock(packed, "minecraft:stone", BlockChange.FLAG_NONE);

        ChunkDirtySet.BlockPutResult result = set.putBlockOcclusionIfBelowCapacity(
            packed, "minecraft:stone", BlockChange.FLAG_OCCLUDED, 1);
        BlockChange change = set.drainAll().blocks().get(0);

        assertEquals(ChunkDirtySet.BlockPutResult.UPDATED, result);
        assertEquals(BlockChange.FLAG_OCCLUDED, change.flags());
    }

    @Test
    public void newOcclusionChangeReportsCapacityExhaustionWithoutReplacingTheQueue() {
        ChunkDirtySet set = new ChunkDirtySet(1L);
        set.putBlock(BlockChange.pack(1, 64, 1), "minecraft:stone", BlockChange.FLAG_NONE);

        ChunkDirtySet.BlockPutResult result = set.putBlockOcclusionIfBelowCapacity(
            BlockChange.pack(2, 64, 2), "minecraft:dirt", BlockChange.FLAG_NONE, 1);

        assertEquals(ChunkDirtySet.BlockPutResult.CAPACITY_EXCEEDED, result);
        assertEquals(1, set.blockCount());
    }
}
