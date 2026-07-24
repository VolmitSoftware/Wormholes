package art.arcane.wormholes.network;

import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.view.ViewSlice;
import art.arcane.wormholes.render.view.OccludedMarker;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewSliceSentinelTest {
    @Test
    void occludedSentinelSurvivesPaletteEncodeDecodeRoundTrip() throws Exception {
        int sizeX = 2;
        int sizeY = 1;
        int sizeZ = 1;
        List<String> palette = List.of("minecraft:stone", OccludedMarker.STATE_STRING);
        short[] indices = new short[]{0, 1};
        int gridLength = ViewSlice.biomeGridSpan(0, sizeX) * ViewSlice.biomeGridSpan(0, sizeY) * ViewSlice.biomeGridSpan(0, sizeZ);
        ViewSlice slice = new ViewSlice(0, 0, 0, sizeX, sizeY, sizeZ, palette, indices,
            new byte[sizeX * sizeY * sizeZ], List.of("minecraft:plains"), new short[gridLength]);

        byte[] bytes = ChunkBulkBuilder.encodeSliceBytes(slice);
        ViewSlice decoded = ViewSlice.read(new DataInputStream(new ByteArrayInputStream(bytes)));

        assertTrue(decoded.palette().contains(OccludedMarker.STATE_STRING), "the reserved sentinel string must survive the wire");
        int decodedSentinel = decoded.palette().indexOf(OccludedMarker.STATE_STRING);
        assertEquals(decodedSentinel, decoded.indices()[1] & 0xFFFF, "the sentinel cell must still reference the sentinel palette entry");
        assertEquals(slice.contentHash(), decoded.contentHash(), "content hash must be preserved across the round trip");
    }
}
