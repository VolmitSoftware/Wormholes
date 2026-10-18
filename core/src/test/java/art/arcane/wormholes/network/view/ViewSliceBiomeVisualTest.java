package art.arcane.wormholes.network.view;

import art.arcane.optics.stream.BiomeVisual;
import art.arcane.optics.math.BlockBox;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ViewSliceBiomeVisualTest {
    @Test
    void peerRoundTripPreservesCustomBiomeVisualsWithEitherEntityLayout() throws IOException {
        BiomeVisual visual = visual(0x123456);
        ViewSlice source = slice(Map.of("iris:biomes/test", visual));
        for (boolean entities : List.of(false, true)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            source.write(new DataOutputStream(bytes), entities);
            ViewSlice decoded = ViewSlice.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
            assertEquals(source.biomeVisuals(), decoded.biomeVisuals());
            assertEquals(source.contentHash(), decoded.contentHash());
        }
    }

    @Test
    void changedVisualDefinitionInvalidatesPeerContentHash() {
        assertNotEquals(slice(Map.of("iris:biomes/test", visual(0x123456))).contentHash(),
            slice(Map.of("iris:biomes/test", visual(0x654321))).contentHash());
    }

    @Test
    @SuppressWarnings("unchecked")
    void croppedPeerSlicesCarryOnlyTheirSampledBiomeDefinitions() {
        ChunkBulkBuilder.SnapshotReader<String, String, String> reader = mock(ChunkBulkBuilder.SnapshotReader.class);
        when(reader.block(eq("snapshot"), anyInt(), anyInt(), anyInt())).thenReturn("minecraft:grass_block");
        when(reader.blockKey("minecraft:grass_block")).thenReturn("minecraft:grass_block");
        when(reader.biome(eq("snapshot"), anyInt(), anyInt(), anyInt())).thenReturn("iris:biomes/test");
        when(reader.biomeKey("iris:biomes/test")).thenReturn("iris:biomes/test");
        when(reader.biomeVisuals("snapshot")).thenReturn(Map.of("iris:biomes/test", visual(0x123456),
            "iris:biomes/outside", visual(0x654321)));
        ChunkBulkBuilder<String, String, String> builder = new ChunkBulkBuilder<>(new HashMap<>(), reader);
        ViewSlice slice = builder.buildSlice(BlockBox.spanning(0, 64, 0, 3, 67, 3), 0, 0, "snapshot", ProjectionRenderMode.PANOPTIC);
        assertEquals(Map.of("iris:biomes/test", visual(0x123456)), slice.biomeVisuals());
    }

    private static ViewSlice slice(Map<String, BiomeVisual> visuals) {
        return new ViewSlice(0, 64, 0, 4, 4, 4, List.of("minecraft:grass_block"), new short[64],
            new byte[64], List.of("iris:biomes/test"), new short[1], Map.of(), visuals);
    }

    private static BiomeVisual visual(int water) {
        return new BiomeVisual(0.7F, 0.8F, true, BiomeVisual.TemperatureModifier.FROZEN,
            water, 0x456789, 0x789ABC, 0xABCDEF, BiomeVisual.GrassModifier.SWAMP);
    }
}
