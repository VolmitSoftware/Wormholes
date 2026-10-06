package art.arcane.wormholes.network.client;

import java.util.ArrayList;
import java.util.List;

import art.arcane.optics.math.BlockBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.BrickCodec;
import art.arcane.optics.stream.ClientViewProtocolException;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;

final class SectionBiomesTest {
    @Test
    void biomePayloadCountsTowardNegotiatedFrameBudget() throws ClientViewProtocolException {
        Brick.BlockEntityCell[] entities = new Brick.BlockEntityCell[24];
        for (int i = 0; i < entities.length; i++) {
            entities[i] = new Brick.BlockEntityCell(i, new byte[2040]);
        }
        Brick brick = Brick.single(0, 3).withBlockEntities(entities);
        assertTrue(BrickCodec.encodedSize(brick) <= ViewStreamLimits.MAX_BRICK_BYTES);
        ArrayList<String> keys = new ArrayList<String>();
        for (int i = 0; i < 64; i++) {
            String prefix = "test:biome_" + i + "_";
            keys.add(prefix + "a".repeat(256 - prefix.length()));
        }
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(1, 2, 0, 0, 0, 3, 3, brick,
            new SectionBiomes(keys, new byte[SectionBiomes.INDEX_BYTES]));
        assertTrue(ClientViewCodec.encodeBody(section).length > ViewStreamLimits.MIN_MAX_FRAME_BYTES);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false);
        ClientViewProtocolException error = assertThrows(ClientViewProtocolException.class, () -> splitter.split(List.of(section), () -> 0));
        assertTrue(error.getMessage().contains("MESH_SECTION"));
    }

    @Test
    void quartPaletteRoundTripsAndOwnsItsIndices() throws ClientViewProtocolException {
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        for (int cell = SectionBiomes.CELLS / 2; cell < SectionBiomes.CELLS; cell++) {
            indices[cell * 2] = 1;
        }
        SectionBiomes biomes = new SectionBiomes(List.of("minecraft:plains", "minecraft:swamp"), indices);
        indices[0] = 1;
        biomes.indices()[0] = 1;
        assertEquals("minecraft:plains", biomes.biome(0));
        assertEquals("minecraft:swamp", biomes.biome(SectionBiomes.CELLS - 1));
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(1, 2, -1, 4, 0, 3, 0, Brick.empty(0), biomes);
        assertEquals(section, ClientViewCodec.decodeS2C(ClientViewCodec.encodeS2C(section, 1, 0), ViewStreamCapability.ALL).message());
    }

    @Test
    void fullGeometricCountCanExceedUnsignedShort() throws ClientViewProtocolException {
        ClientViewMessage.MeshBegin begin = new ClientViewMessage.MeshBegin(1, 2, new BlockBox(-512, -512, 0, 1025, 1025, 512), 135200);
        assertEquals(begin, ClientViewCodec.decodeS2C(ClientViewCodec.encodeS2C(begin, 1, 0), ViewStreamCapability.ALL).message());
    }

    @Test
    void malformedIndicesAndOversizedPalettesAreRejected() throws ClientViewProtocolException {
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        indices[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> new SectionBiomes(List.of("a:b", "c:d"), indices));
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(1, 2, 0, 0, 0, 3, 0, Brick.empty(0), SectionBiomes.NONE);
        byte[] encoded = ClientViewCodec.encodeS2C(section, 1, 0);
        encoded[encoded.length - 2] = (byte) 0xFF;
        encoded[encoded.length - 1] = (byte) 0xFF;
        assertThrows(ClientViewProtocolException.class, () -> ClientViewCodec.decodeS2C(encoded, ViewStreamCapability.ALL));
    }

    @Test
    void haloSupportsEveryDistinctQuartBiomeAndUnsignedShortIndices() throws ClientViewProtocolException {
        ArrayList<String> palette = new ArrayList<String>(SectionBiomes.CELLS);
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        for (int cell = 0; cell < SectionBiomes.CELLS; cell++) {
            palette.add("test:biome_" + cell);
            indices[cell * 2] = (byte) cell;
            indices[cell * 2 + 1] = (byte) (cell >>> 8);
        }
        SectionBiomes biomes = new SectionBiomes(palette, indices);
        assertEquals("test:biome_0", biomes.biome(SectionBiomes.cell(-8, -8, -8)));
        assertEquals("test:biome_511", biomes.biome(SectionBiomes.cell(23, 23, 23)));
        assertEquals(-1, SectionBiomes.cell(-9, 0, 0));
        assertEquals(-1, SectionBiomes.cell(0, 24, 0));
        ClientViewMessage.MeshSection section = new ClientViewMessage.MeshSection(1, 2, 0, 0, 0, 3, 0, Brick.empty(0), biomes);
        assertEquals(section, ClientViewCodec.decodeS2C(ClientViewCodec.encodeS2C(section, 1, 0), ViewStreamCapability.ALL).message());
    }
}
