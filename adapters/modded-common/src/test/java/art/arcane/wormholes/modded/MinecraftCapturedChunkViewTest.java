package art.arcane.wormholes.modded;

import art.arcane.optics.math.CellKeys;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.view.ContentView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MinecraftCapturedChunkViewTest extends MinecraftTestBase {
    @Test
    @SuppressWarnings("unchecked")
    public void samplesCapturedSectionsAndAirForEmptyOnes() {
        UUID worldId = UUID.randomUUID();
        PalettedContainer<BlockState>[] sections = new PalettedContainer[24];
        sections[8] = MinecraftPlateCaptureSourceTest.container();
        sections[8].set(5, 4, 7, stone());
        BlockEntitySample sample = new BlockEntitySample("minecraft:sign", new byte[] {1, 2, 3});
        MinecraftPlateCaptureSource.CapturedChunk chunk = new MinecraftPlateCaptureSource.CapturedChunk(-4, sections,
            Map.of(CellKeys.pack(5, 68, 7), sample), false, null, 0, new String[0][]);
        Long2ObjectOpenHashMap<MinecraftPlateCaptureSource.CapturedChunk> chunks = new Long2ObjectOpenHashMap<>();
        chunks.put(CellKeys.chunkKey(0, 0), chunk);
        MinecraftCapturedChunkView view = new MinecraftCapturedChunkView(worldId, -64, 320, 9L,
            new PlateCaptureJob.Captured<>(new ViewPlateBuilder.Footprint(0, 0, 0, 0, 1L), chunks), biome -> -1);
        assertEquals(worldId, view.worldId());
        assertEquals(9L, view.getRevision());
        assertEquals(-64, view.getMinHeight());
        assertEquals(320, view.getMaxHeight());
        assertSame(stone(), view.sampleBlockData(5, 68, 7));
        assertSame(stone(), view.material(5, 68, 7));
        assertTrue(view.sampleBlockData(6, 68, 7).isAir());
        assertTrue(view.sampleBlockData(5, 100, 7).isAir());
        assertNull(view.sampleBlockData(16, 68, 0));
        assertNull(view.sampleBlockData(5, -65, 7));
        assertNull(view.sampleBlockData(5, 320, 7));
        assertTrue(view.isChunkReady(0, 0));
        assertFalse(view.isChunkReady(16, 0));
        assertSame(sample, view.sampleBlockEntity(5, 68, 7));
        assertNull(view.sampleBlockEntity(5, 69, 7));
        assertFalse(view.blockEntitiesComplete(0, 0));
        assertTrue(view.blockEntitiesComplete(16, 0));
        assertNull(view.sampleBiome(5, 68, 7));
        assertEquals(ContentView.LIGHT_UNAVAILABLE, view.getLight(5, 68, 7));
        assertEquals(0, view.getSkyDarken());
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }
}
