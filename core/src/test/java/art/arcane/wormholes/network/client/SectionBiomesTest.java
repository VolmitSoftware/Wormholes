package art.arcane.wormholes.network.client;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import art.arcane.wormholes.render.plate.PlateBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SectionBiomesTest {
    @Test
    void biomePayloadCountsTowardNegotiatedFrameBudget() throws ClientViewProtocolException {
        Brick.BlockEntityCell[] entities = new Brick.BlockEntityCell[24];
        for (int i = 0; i < entities.length; i++) {
            entities[i] = new Brick.BlockEntityCell(i, new byte[2040]);
        }
        Brick brick = Brick.single(0, 3).withBlockEntities(entities);
        assertTrue(BrickCodec.encodedSize(brick) <= ClientViewProtocol.MAX_BRICK_BYTES);
        ArrayList<String> keys = new ArrayList<String>();
        for (int i = 0; i < 64; i++) {
            String prefix = "test:biome_" + i + "_";
            keys.add(prefix + "a".repeat(256 - prefix.length()));
        }
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(1, 2, 0, 0, 0, 3, 3, brick,
            new SectionBiomes(keys, new byte[64]));
        assertTrue(ClientViewCodec.encodeBody(section).length > ClientViewProtocol.MIN_MAX_FRAME_BYTES);
        FrameSplitter splitter = new FrameSplitter(ClientViewProtocol.MIN_MAX_FRAME_BYTES, false);
        ClientViewProtocolException error = assertThrows(ClientViewProtocolException.class, () -> splitter.split(List.of(section), () -> 0));
        assertTrue(error.getMessage().contains("MESH_SECTION"));
    }

    @Test
    void quartPaletteRoundTripsAndOwnsItsIndices() throws ClientViewProtocolException {
        byte[] indices = new byte[64];
        Arrays.fill(indices, 32, 64, (byte) 1);
        SectionBiomes biomes = new SectionBiomes(List.of("minecraft:plains", "minecraft:swamp"), indices);
        indices[0] = 1;
        biomes.indices()[0] = 1;
        assertEquals("minecraft:plains", biomes.biome(0));
        assertEquals("minecraft:swamp", biomes.biome(63));
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(1, 2, -1, 4, 0, 3, 0, Brick.empty(0), biomes);
        assertEquals(section, ClientViewCodec.decodeS2C(ClientViewCodec.encodeS2C(section, 1, 0), ClientViewCapability.ALL).message());
    }

    @Test
    void fullGeometricCountCanExceedUnsignedShort() throws ClientViewProtocolException {
        ClientViewMessage.MeshBegin begin = new ClientViewMessage.MeshBegin(1, 2, new PlateBox(-512, -512, 0, 1025, 1025, 512), 135200);
        assertEquals(begin, ClientViewCodec.decodeS2C(ClientViewCodec.encodeS2C(begin, 1, 0), ClientViewCapability.ALL).message());
    }

    @Test
    void malformedIndicesAndOversizedPalettesAreRejected() throws ClientViewProtocolException {
        byte[] indices = new byte[64];
        indices[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> new SectionBiomes(List.of("a:b", "c:d"), indices));
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(1, 2, 0, 0, 0, 3, 0, Brick.empty(0), SectionBiomes.NONE);
        byte[] encoded = ClientViewCodec.encodeS2C(section, 1, 0);
        encoded[encoded.length - 1] = 65;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(encoded, ClientViewCapability.ALL));
    }
}
