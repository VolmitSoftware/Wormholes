package art.arcane.wormholes.modded;

import art.arcane.optics.view.CachedSection;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftSectionSourceTest extends MinecraftTestBase {
    @Test
    public void airOnlySectionsFillEveryCellWithTheSharedAirState() {
        Fixture fixture = fixture();
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.hasOnlyAir()).thenReturn(true);
        when(fixture.chunk().getSection(4)).thenReturn(section);
        CachedSection.Builder<BlockState, BlockState> builder = new CachedSection.Builder<>(MinecraftProjectorBlocks.INSTANCE);
        assertTrue(fixture.source().capture(0, 0, 0, builder));
        CachedSection<BlockState, BlockState> cached = builder.build(0, 0, 0, 1);
        assertEquals(1, cached.paletteSize());
        assertSame(air(), cached.data(CachedSection.index(3, 4, 5)));
        assertSame(air(), cached.data(CachedSection.index(15, 15, 15)));
        assertFalse(cached.occluding(CachedSection.index(0, 0, 0)));
        verify(section, times(0)).getBlockState(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void cellsAreIndexedInVanillaOrder() {
        Fixture fixture = fixture();
        LevelChunkSection section = mock(LevelChunkSection.class);
        when(section.hasOnlyAir()).thenReturn(false);
        when(section.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0);
            int y = invocation.getArgument(1);
            int z = invocation.getArgument(2);
            return x == 1 && y == 2 && z == 3 ? stone() : air();
        });
        when(fixture.chunk().getSection(6)).thenReturn(section);
        CachedSection.Builder<BlockState, BlockState> builder = new CachedSection.Builder<>(MinecraftProjectorBlocks.INSTANCE);
        assertTrue(fixture.source().capture(0, 2, 0, builder));
        CachedSection<BlockState, BlockState> cached = builder.build(0, 2, 0, 1);
        assertSame(stone(), cached.data(CachedSection.index(1, 2, 3)));
        assertSame(air(), cached.data(CachedSection.index(3, 2, 1)));
        assertSame(air(), cached.data(CachedSection.index(2, 1, 3)));
        assertTrue(cached.occluding((2 << 8) | (3 << 4) | 1));
        assertEquals(2, cached.paletteSize());
    }

    @Test
    public void missingColumnsAndSectionsOutsideTheChunkAreNotCaptured() {
        Fixture fixture = fixture();
        CachedSection.Builder<BlockState, BlockState> builder = new CachedSection.Builder<>(MinecraftProjectorBlocks.INSTANCE);
        assertFalse(fixture.source().columnAvailable(5, 5));
        assertFalse(fixture.source().capture(5, 0, 5, builder));
        assertTrue(fixture.source().columnAvailable(0, 0));
        assertFalse(fixture.source().capture(0, -5, 0, builder));
        assertFalse(fixture.source().capture(0, 20, 0, builder));
    }

    @Test
    public void columnsAreLookedUpOncePerTickUntilDiscarded() {
        Fixture fixture = fixture();
        assertTrue(fixture.source().columnAvailable(0, 0));
        assertTrue(fixture.source().columnAvailable(0, 0));
        verify(fixture.chunks(), times(1)).getChunkNow(0, 0);
        fixture.source().discardColumn(0, 0);
        assertTrue(fixture.source().columnAvailable(0, 0));
        verify(fixture.chunks(), times(2)).getChunkNow(0, 0);
        fixture.source().endTick();
        assertTrue(fixture.source().columnAvailable(0, 0));
        verify(fixture.chunks(), times(3)).getChunkNow(0, 0);
    }

    private static Fixture fixture() {
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        LevelChunk chunk = mock(LevelChunk.class);
        when(level.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(0, 0)).thenReturn(chunk);
        when(chunk.getSectionsCount()).thenReturn(24);
        when(chunk.getSectionIndexFromSectionY(anyInt())).thenAnswer(invocation -> invocation.getArgument(0, Integer.class) + 4);
        return new Fixture(new MinecraftSectionSource(level, air()), chunks, chunk);
    }

    private record Fixture(MinecraftSectionSource source, ServerChunkCache chunks, LevelChunk chunk) {
    }

    private static BlockState air() {
        return Blocks.AIR.defaultBlockState();
    }

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }
}
