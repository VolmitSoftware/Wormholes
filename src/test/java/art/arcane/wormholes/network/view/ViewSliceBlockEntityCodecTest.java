package art.arcane.wormholes.network.view;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;

final class ViewSliceBlockEntityCodecTest {
    @Test
    void theNewLayoutRoundTripsBlockEntitiesAndTheOldLayoutIsByteIdentical() throws IOException {
        Map<Long, BlockEntitySample> entities = new HashMap<Long, BlockEntitySample>();
        entities.put(Long.valueOf(ProjectionCellKey.pack(1, 62, 2)), new BlockEntitySample("minecraft:sign", new byte[] {1, 2, 3}));
        entities.put(Long.valueOf(ProjectionCellKey.pack(5, 65, 9)), new BlockEntitySample("minecraft:skull", new byte[] {9}));
        ViewSlice withEntities = slice(entities);
        ViewSlice bare = slice(new HashMap<Long, BlockEntitySample>());

        byte[] newLayout = encode(withEntities, true);
        ViewSlice decoded = ViewSlice.read(new DataInputStream(new ByteArrayInputStream(newLayout)));
        assertEquals(withEntities.blockEntities(), decoded.blockEntities());
        assertEquals(withEntities.contentHash(), decoded.contentHash());

        byte[] baseLayout = encode(withEntities, false);
        assertArrayEquals(encode(bare, false), baseLayout, "peers without the capability receive the base layout");
        assertArrayEquals(encode(bare), baseLayout, "the default writer stays the base layout");
        assertNotEquals(baseLayout[0], newLayout[0], "the leading byte says which layout follows");
        ViewSlice oldDecoded = ViewSlice.read(new DataInputStream(new ByteArrayInputStream(baseLayout)));
        assertTrue(oldDecoded.blockEntities().isEmpty());
        assertEquals(bare.contentHash(), oldDecoded.contentHash(), "the base layout hash is unchanged");
        assertNotEquals(bare.contentHash(), withEntities.contentHash(), "samples change the hash only when present");
    }

    @Test
    void aBareNewLayoutSliceIsReadableByANewPeer() throws IOException {
        ViewSlice bare = slice(new HashMap<Long, BlockEntitySample>());
        ViewSlice decoded = ViewSlice.read(new DataInputStream(new ByteArrayInputStream(encode(bare, true))));
        assertTrue(decoded.blockEntities().isEmpty());
        assertEquals(bare.contentHash(), decoded.contentHash());
    }

    private static ViewSlice slice(Map<Long, BlockEntitySample> entities) {
        int sizeX = 16;
        int sizeY = 8;
        int sizeZ = 16;
        int cells = sizeX * sizeY * sizeZ;
        int gridLength = ViewSlice.biomeGridSpan(0, sizeX) * ViewSlice.biomeGridSpan(60, sizeY) * ViewSlice.biomeGridSpan(0, sizeZ);
        short[] indices = new short[cells];
        for (int i = 0; i < cells; i++) {
            indices[i] = (short) (i % 2);
        }
        return new ViewSlice(0, 60, 0, sizeX, sizeY, sizeZ, List.of("minecraft:air", "minecraft:stone"),
            indices, new byte[cells], List.of("minecraft:plains"), new short[gridLength], entities);
    }

    private static byte[] encode(ViewSlice slice, boolean withBlockEntities) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        slice.write(new DataOutputStream(buffer), withBlockEntities);
        return buffer.toByteArray();
    }

    private static byte[] encode(ViewSlice slice) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        slice.write(new DataOutputStream(buffer));
        return buffer.toByteArray();
    }
}
