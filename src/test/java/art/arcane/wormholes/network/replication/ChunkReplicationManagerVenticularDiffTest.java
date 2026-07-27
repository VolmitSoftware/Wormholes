package art.arcane.wormholes.network.replication;

import art.arcane.wormholes.network.WireMessage;
import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.view.OccludedMarker;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkReplicationManagerVenticularDiffTest {
    private static final String PEER = "peer-v";

    @Test
    void venticularPeerReceivesSentinelForOccludedCellsAndRealForExposed(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        UUID portalId = UUID.randomUUID();
        long chunkKey = ViewSlice.columnKey(0, 0);
        ReplicationStreamKey stream = ReplicationTestStream.stream(portalId, world, chunkKey, ProjectionRenderMode.VENTICULAR);
        manager.subscribe(PEER, portalId, world, stream);
        byte[] payload = bulkPayload();
        manager.sendBulk(PEER, portalId, stream, payload, contentHashOf(payload));
        sink.clear();

        BlockChange occluded = new BlockChange(BlockChange.pack(3, 80, 7), "minecraft:stone", BlockChange.FLAG_OCCLUDED);
        BlockChange exposed = new BlockChange(BlockChange.pack(4, 81, 7), "minecraft:dirt", BlockChange.FLAG_NONE);
        manager.onChunkDrain(world, chunkKey, List.of(occluded, exposed), List.of(), List.of());
        manager.flushTick();

        List<BlockChange> sent = sentBlocks(sink);
        assertEquals(2, sent.size());
        assertEquals(OccludedMarker.STATE_STRING, sent.get(0).state(), "occluded cell must be substituted with the sentinel");
        assertEquals(BlockChange.FLAG_NONE, sent.get(0).flags(), "the capture-only occluded flag must never reach the wire");
        assertEquals("minecraft:dirt", sent.get(1).state(), "exposed cell must keep its real state");
        assertEquals(BlockChange.FLAG_NONE, sent.get(1).flags());
    }

    @Test
    void panopticPeerKeepsRealStateAndStripsOccludedFlag(@TempDir Path dir) {
        TestNetworkSink sink = new TestNetworkSink(dir);
        ChunkReplicationManager manager = sink.getReplicationManager();
        World world = StubWorld.create(UUID.randomUUID());
        UUID portalId = UUID.randomUUID();
        long chunkKey = ViewSlice.columnKey(0, 0);
        ReplicationStreamKey stream = ReplicationTestStream.stream(portalId, world, chunkKey);
        manager.subscribe(PEER, portalId, world, stream);
        byte[] payload = bulkPayload();
        manager.sendBulk(PEER, portalId, stream, payload, contentHashOf(payload));
        sink.clear();

        BlockChange occluded = new BlockChange(BlockChange.pack(3, 80, 7), "minecraft:stone", BlockChange.FLAG_OCCLUDED);
        manager.onChunkDrain(world, chunkKey, List.of(occluded), List.of(), List.of());
        manager.flushTick();

        List<BlockChange> sent = sentBlocks(sink);
        assertEquals(1, sent.size());
        assertEquals("minecraft:stone", sent.get(0).state(), "a panoptic peer must never see the sentinel");
        assertEquals(BlockChange.FLAG_NONE, sent.get(0).flags(), "the capture-only occluded flag must be stripped before the wire");
    }

    private static List<BlockChange> sentBlocks(TestNetworkSink sink) {
        WireMessage.ChunkDiff diff = (WireMessage.ChunkDiff) sink.sentTo(PEER).get(0);
        return diff.batches().get(0).blocks();
    }

    private static long contentHashOf(byte[] payload) {
        try {
            return ViewSlice.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload))).contentHash();
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static byte[] bulkPayload() {
        int sizeX = 16;
        int sizeY = 24;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        short[] indices = new short[cells];
        byte[] light = new byte[cells];
        short[] biomes = new short[ViewSlice.biomeGridSpan(0, sizeX) * ViewSlice.biomeGridSpan(60, sizeY) * ViewSlice.biomeGridSpan(0, sizeZ)];
        ViewSlice slice = new ViewSlice(0, 60, 0, sizeX, sizeY, sizeZ,
            List.of("minecraft:stone"), indices, light, List.of("minecraft:plains"), biomes);
        try {
            return ChunkBulkBuilder.encodeSliceBytes(slice);
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }
}
