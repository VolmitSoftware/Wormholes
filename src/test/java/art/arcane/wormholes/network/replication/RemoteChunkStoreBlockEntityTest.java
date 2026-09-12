package art.arcane.wormholes.network.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.blockentity.BlockEntityCapturer;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;

final class RemoteChunkStoreBlockEntityTest {
    private static final ReplicationStreamKey STREAM = new ReplicationStreamKey(
        UUID.fromString("00000000-0000-0000-0000-0000000000e1"), UUID.fromString("00000000-0000-0000-0000-0000000000e2"),
        ViewSlice.columnKey(0, 0), ProjectionRenderMode.PANOPTIC);

    @Test
    void bulkAndDiffBlockEntitiesLandInTheSliceAndBlockChangesEvictThem() throws IOException {
        BlockEntitySample sign = new BlockEntitySample("minecraft:sign", new byte[] {10, 0, 0, 0});
        Map<Long, BlockEntitySample> entities = new HashMap<Long, BlockEntitySample>();
        entities.put(Long.valueOf(ProjectionCellKey.pack(3, 64, 5)), sign);
        ViewSlice slice = slice(entities);
        RemoteChunkStore store = new RemoteChunkStore();

        RemoteChunkStore.ReplicatedChunk chunk = store.applyBulk(new ChunkBulk(STREAM, 1L, encode(slice, true)));
        assertEquals(sign, chunk.slice().blockEntityAt(3, 64, 5));
        long hashWithSign = store.hashAt(STREAM);

        BlockEntitySample banner = new BlockEntitySample("minecraft:banner", new byte[] {11, 0, 0, 0});
        BlockEntityDiff diff = new BlockEntityDiff(BlockChange.pack(4, 64, 6), BlockEntityCapturer.encode(banner));
        store.applyDiff(new ChunkDiffBatch(STREAM, 2L, List.of(), List.of(), List.of(diff)));
        assertEquals(banner, chunk.slice().blockEntityAt(4, 64, 6));
        assertNotEquals(hashWithSign, store.hashAt(STREAM), "block entities are part of the content hash");

        BlockChange stone = new BlockChange(BlockChange.pack(3, 64, 5), "minecraft:stone", BlockChange.FLAG_NONE);
        store.applyDiff(new ChunkDiffBatch(STREAM, 3L, List.of(stone), List.of(), List.of()));
        assertNull(chunk.slice().blockEntityAt(3, 64, 5), "a block change to a plain block drops its block entity");
        assertEquals(banner, chunk.slice().blockEntityAt(4, 64, 6));
    }

    @Test
    void aBaseLayoutBulkFromAPeerWithoutTheCapabilityDecodesWithNoBlockEntities() throws IOException {
        Map<Long, BlockEntitySample> entities = new HashMap<Long, BlockEntitySample>();
        entities.put(Long.valueOf(ProjectionCellKey.pack(3, 64, 5)), new BlockEntitySample("minecraft:sign", new byte[] {1}));
        ViewSlice slice = slice(entities);
        RemoteChunkStore store = new RemoteChunkStore();

        RemoteChunkStore.ReplicatedChunk chunk = store.applyBulk(new ChunkBulk(STREAM, 1L, encode(slice, false)));
        assertTrue(chunk.slice().blockEntities().isEmpty());
    }

    private static ViewSlice slice(Map<Long, BlockEntitySample> entities) {
        int sizeX = 16;
        int sizeY = 8;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        int gridLength = ViewSlice.biomeGridSpan(0, sizeX) * ViewSlice.biomeGridSpan(60, sizeY) * ViewSlice.biomeGridSpan(0, sizeZ);
        return new ViewSlice(0, 60, 0, sizeX, sizeY, sizeZ, new java.util.ArrayList<String>(List.of("minecraft:air", "minecraft:oak_sign")),
            new short[cells], new byte[cells], List.of("minecraft:plains"), new short[gridLength], entities);
    }

    private static byte[] encode(ViewSlice slice, boolean withBlockEntities) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        slice.write(new DataOutputStream(buffer), withBlockEntities);
        return buffer.toByteArray();
    }
}
