package art.arcane.optics.stream;

import java.util.ArrayList;
import java.util.List;

import art.arcane.optics.math.BlockBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SectionBiomesTest {
    @Test
    void biomePayloadCountsTowardNegotiatedFrameBudget() throws ViewStreamProtocolException {
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
        ViewStreamMessage.MeshSection section = new ViewStreamMessage.MeshSection(1, 2, 0, 0, 0, 3, 3, brick,
            new SectionBiomes(keys, new byte[SectionBiomes.INDEX_BYTES]));
        assertTrue(ViewStreamFixtures.CODEC.encodeBody(section).length > ViewStreamLimits.MIN_MAX_FRAME_BYTES);
        FrameSplitter splitter = new FrameSplitter(ViewStreamLimits.MIN_MAX_FRAME_BYTES, false, ViewStreamFixtures.CODEC);
        ViewStreamProtocolException error = assertThrows(ViewStreamProtocolException.class, () -> splitter.split(List.of(section), () -> 0));
        assertTrue(error.getMessage().contains("MESH_SECTION"));
    }

    @Test
    void quartPaletteRoundTripsAndOwnsItsIndices() throws ViewStreamProtocolException {
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        for (int cell = SectionBiomes.CELLS / 2; cell < SectionBiomes.CELLS; cell++) {
            indices[cell * 2] = 1;
        }
        SectionBiomes biomes = new SectionBiomes(List.of("minecraft:plains", "minecraft:swamp"), indices);
        indices[0] = 1;
        biomes.indices()[0] = 1;
        assertEquals("minecraft:plains", biomes.biome(0));
        assertEquals("minecraft:swamp", biomes.biome(SectionBiomes.CELLS - 1));
        ViewStreamMessage.MeshSection section = new ViewStreamMessage.MeshSection(1, 2, -1, 4, 0, 3, 0, Brick.empty(0), biomes);
        assertEquals(section, ViewStreamFixtures.CODEC.decodeS2C(ViewStreamFixtures.CODEC.encodeS2C(section, 1, 0), ViewStreamCapability.ALL).message());
    }

    @Test
    void fullGeometricCountCanExceedUnsignedShort() throws ViewStreamProtocolException {
        ViewStreamMessage.MeshBegin begin = new ViewStreamMessage.MeshBegin(1, 2, new BlockBox(-512, -512, 0, 1025, 1025, 512), 135200);
        assertEquals(begin, ViewStreamFixtures.CODEC.decodeS2C(ViewStreamFixtures.CODEC.encodeS2C(begin, 1, 0), ViewStreamCapability.ALL).message());
    }

    @Test
    void malformedIndicesAndOversizedPalettesAreRejected() throws ViewStreamProtocolException {
        byte[] indices = new byte[SectionBiomes.INDEX_BYTES];
        indices[4] = 2;
        assertThrows(IllegalArgumentException.class, () -> new SectionBiomes(List.of("a:b", "c:d"), indices));
        ViewStreamMessage.MeshSection section = new ViewStreamMessage.MeshSection(1, 2, 0, 0, 0, 3, 0, Brick.empty(0), SectionBiomes.NONE);
        byte[] encoded = ViewStreamFixtures.CODEC.encodeS2C(section, 1, 0);
        encoded[encoded.length - 2] = (byte) 0xFF;
        encoded[encoded.length - 1] = (byte) 0xFF;
        assertThrows(ViewStreamProtocolException.class, () -> ViewStreamFixtures.CODEC.decodeS2C(encoded, ViewStreamCapability.ALL));
    }

    @Test
    void haloSupportsEveryDistinctQuartBiomeAndUnsignedShortIndices() throws ViewStreamProtocolException {
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
        ViewStreamMessage.MeshSection section = new ViewStreamMessage.MeshSection(1, 2, 0, 0, 0, 3, 0, Brick.empty(0), biomes);
        assertEquals(section, ViewStreamFixtures.CODEC.decodeS2C(ViewStreamFixtures.CODEC.encodeS2C(section, 1, 0), ViewStreamCapability.ALL).message());
    }
}
