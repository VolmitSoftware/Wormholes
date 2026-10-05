package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.render.view.SectionCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftProjectionWorldViewTest extends MinecraftTestBase {
    private static final int MIN_SECTION_Y = -4;
    private static final int MAX_SECTION_Y = 19;

    @Test
    public void samplesOnlyAlreadyLoadedChunksAndRespectsHeight() {
        Fixture fixture = fixture();
        try (MinecraftProjectionWorldView view = fixture.view()) {
            assertNull(view.sampleBlockData(16, 64, 0));
            assertFalse(view.isChunkReady(16, 0));
            LevelChunk chunk = mock(LevelChunk.class);
            BlockState stone = Blocks.STONE.defaultBlockState();
            when(chunk.getBlockState(any(BlockPos.class))).thenReturn(stone);
            when(fixture.chunks().getChunkNow(1, 0)).thenReturn(chunk);
            assertSame(stone, view.sampleBlockData(16, 64, 0));
            assertTrue(view.isChunkReady(16, 0));
            assertNull(view.sampleBlockData(16, -65, 0));
            assertNull(view.sampleBlockData(16, 320, 0));
            verify(fixture.level(), never()).getBlockState(any(BlockPos.class));
        }
    }

    @Test
    public void duplicateChunkRequestsShareLeaseAndClosingReleasesIt() {
        Fixture fixture = fixture();
        MinecraftProjectionWorldView view = fixture.view();
        view.requestChunk(-1, 3);
        view.requestChunk(-16, 9);
        verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
        view.close();
        verify(fixture.lease()).close();
        assertFalse(view.isChunkReady(-1, 3));
    }

    @Test
    public void leaseArrivalMarksOnlyItsColumn() {
        Fixture fixture = fixture();
        try (MinecraftProjectionWorldView view = fixture.view()) {
            long revision = view.getRevision();
            view.requestChunk(3, 5);
            verify(fixture.projections(), never()).columnChanged(any(), anyInt(), anyInt());
            fixture.ready().complete(true);
            verify(fixture.projections(), times(1)).columnChanged(fixture.level(), 0, 0);
            verify(fixture.projections(), times(1)).columnChanged(any(), anyInt(), anyInt());
            verify(fixture.projections(), never()).blockChanged(any(), any());
            assertEquals(revision, view.getRevision());
        }
    }

    @Test
    public void leaseArrivalAfterCloseMarksNothing() {
        Fixture fixture = fixture();
        MinecraftProjectionWorldView view = fixture.view();
        view.requestChunk(-20, 40);
        view.close();
        fixture.ready().complete(true);
        verify(fixture.projections(), never()).columnChanged(any(), anyInt(), anyInt());
    }

    @Test
    public void cachedSectionsServeRepeatSamplesWithoutTouchingTheChunkSource() {
        Fixture fixture = fixture();
        BlockState stone = Blocks.STONE.defaultBlockState();
        LevelChunk loaded = chunk(new AtomicReference<>(stone), -1, 1);
        when(fixture.chunks().getChunkNow(0, 0)).thenReturn(loaded);
        try (MinecraftProjectionWorldView view = fixture.view()) {
            assertSame(stone, view.sampleBlockData(8, 8, 8));
            clearInvocations(fixture.chunks());
            assertSame(stone, view.sampleBlockData(9, 9, 9));
            assertSame(stone, view.sampleMaterial(8, 8, 8));
            verify(fixture.chunks(), never()).getChunkNow(anyInt(), anyInt());
            assertEquals(1, view.sections().size());
        }
    }

    @Test
    public void buriedDepthComesFromTheCachedSectionsAndTheirNeighbours() {
        Fixture fixture = fixture();
        BlockState stone = Blocks.STONE.defaultBlockState();
        LevelChunk loaded = chunk(new AtomicReference<>(stone), -1, 1);
        for (int chunkX = -1; chunkX <= 1; chunkX++) {
            for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
                when(fixture.chunks().getChunkNow(chunkX, chunkZ)).thenReturn(loaded);
            }
        }
        try (MinecraftProjectionWorldView view = fixture.view()) {
            assertEquals(2, view.buriedDepth(8, 8, 8));
            assertEquals(2, view.buriedDepth(0, 8, 8));
            assertEquals(2, view.buriedDepth(8, 8, 15));
            assertEquals(0, view.buriedDepth(8, 31, 8));
            assertEquals(0, view.buriedDepth(8, 40, 8));
            assertEquals(-1, view.buriedDepth(8, -65, 8));
            assertEquals(-1, view.buriedDepth(8, 8, 40));
        }
    }

    @Test
    public void blockMarksEvictTheirSectionSoTheNextSampleRecaptures() {
        Fixture fixture = fixture();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        AtomicReference<BlockState> state = new AtomicReference<>(stone);
        LevelChunk loaded = chunk(state, -1, 1);
        when(fixture.chunks().getChunkNow(0, 0)).thenReturn(loaded);
        try (MinecraftProjectionWorldView view = fixture.view()) {
            assertSame(stone, view.sampleBlockData(8, 8, 8));
            assertSame(stone, view.sampleBlockData(8, 24, 8));
            state.set(dirt);
            assertSame(stone, view.sampleBlockData(8, 8, 8));
            assertSame(stone, view.sampleBlockData(8, 24, 8));
            view.sections().blockChanged(8, 8, 8);
            assertSame(dirt, view.sampleBlockData(8, 8, 8));
            assertSame(stone, view.sampleBlockData(8, 24, 8));
            view.sections().columnChanged(0, 0);
            assertSame(dirt, view.sampleBlockData(8, 24, 8));
        }
    }

    @Test
    public void chunkArrivalCapturesWantedSectionsThatOutliveTheChunk() {
        Fixture fixture = fixture();
        BlockState stone = Blocks.STONE.defaultBlockState();
        try (MinecraftProjectionWorldView view = fixture.view()) {
            assertNull(view.sampleBlockData(16, 64, 0));
            verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
            LevelChunk loaded = chunk(new AtomicReference<>(stone), 4, 4);
            when(fixture.chunks().getChunkNow(1, 0)).thenReturn(loaded);
            view.chunkArrived(1, 0);
            assertEquals(1, view.sections().size());
            when(fixture.chunks().getChunkNow(1, 0)).thenReturn(null);
            assertSame(stone, view.sampleBlockData(16, 64, 0));
            assertTrue(view.isChunkReady(16, 0));
            view.chunkArrived(1, 0);
            assertEquals(1, view.sections().size());
        }
    }

    @Test
    public void leaseArrivalKeepsWantedSectionsThroughItsColumnMark() {
        Fixture fixture = fixture();
        BlockState stone = Blocks.STONE.defaultBlockState();
        try (MinecraftProjectionWorldView view = fixture.view()) {
            doAnswer(invocation -> {
                view.sections().columnChanged(invocation.getArgument(1, Integer.class), invocation.getArgument(2, Integer.class));
                return null;
            }).when(fixture.projections()).columnChanged(any(), anyInt(), anyInt());
            assertNull(view.sampleBlockData(16, 64, 0));
            LevelChunk loaded = chunk(new AtomicReference<>(stone), 4, 4);
            when(fixture.chunks().getChunkNow(1, 0)).thenReturn(loaded);
            fixture.ready().complete(true);
            verify(fixture.projections(), times(1)).columnChanged(fixture.level(), 1, 0);
            when(fixture.chunks().getChunkNow(1, 0)).thenReturn(null);
            assertEquals(1, view.sections().size());
            assertSame(stone, view.sampleBlockData(16, 64, 0));
        }
    }

    @Test
    public void uncachedViewsReadTheLiveChunkOnly() {
        Fixture fixture = fixture();
        BlockState stone = Blocks.STONE.defaultBlockState();
        when(fixture.level().getMinSectionY()).thenReturn(MIN_SECTION_Y);
        when(fixture.level().getMaxSectionY()).thenReturn(MAX_SECTION_Y);
        LevelChunk loaded = chunk(new AtomicReference<>(stone), -1, 1);
        when(fixture.chunks().getChunkNow(0, 0)).thenReturn(loaded);
        try (MinecraftProjectionWorldView view = MinecraftProjectionWorldView.uncached(fixture.runtime(), fixture.level())) {
            assertSame(stone, view.sampleBlockData(8, 8, 8));
            assertEquals(0, view.sections().size());
            assertEquals(-1, view.buriedDepth(8, 8, 8));
            assertEquals(fixture.view().worldId(), view.worldId());
        }
    }

    static LevelChunk chunk(AtomicReference<BlockState> state, int solidMinSectionY, int solidMaxSectionY) {
        LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getBlockState(any(BlockPos.class))).thenAnswer(invocation -> state.get());
        when(chunk.getSectionsCount()).thenReturn(MAX_SECTION_Y - MIN_SECTION_Y + 1);
        when(chunk.getMinSectionY()).thenReturn(MIN_SECTION_Y);
        when(chunk.getSectionIndexFromSectionY(anyInt())).thenAnswer(invocation -> invocation.getArgument(0, Integer.class) - MIN_SECTION_Y);
        LevelChunkSection air = mock(LevelChunkSection.class);
        when(air.hasOnlyAir()).thenReturn(true);
        LevelChunkSection solid = mock(LevelChunkSection.class);
        when(solid.hasOnlyAir()).thenReturn(false);
        when(solid.getBlockState(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> state.get());
        when(chunk.getSection(anyInt())).thenAnswer(invocation -> {
            int sectionY = invocation.getArgument(0, Integer.class) + MIN_SECTION_Y;
            return sectionY >= solidMinSectionY && sectionY <= solidMaxSectionY ? solid : air;
        });
        return chunk;
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        MinecraftServer server = mock(MinecraftServer.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        MinecraftProjectionService projections = mock(MinecraftProjectionService.class);
        ChunkLease lease = mock(ChunkLease.class);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        when(runtime.server()).thenReturn(server);
        when(runtime.leases()).thenReturn(leases);
        when(runtime.projections()).thenReturn(projections);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(MinecraftTestSettings.defaults());
        when(level.getChunkSource()).thenReturn(chunks);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(320);
        when(leases.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
        when(lease.ready()).thenReturn(ready);
        when(runtime.schedule(any(), anyLong())).thenReturn(true);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(server).execute(any(Runnable.class));
        SectionCache<BlockState, BlockState> cache = new SectionCache<>(MinecraftProjectorBlocks.INSTANCE, SectionCache.Limits.from(true, 64, 1024, 200));
        cache.tick(1);
        SectionCache<BlockState, BlockState>.WorldSections sections = cache.world(
            new MinecraftSectionSource(level, Blocks.AIR.defaultBlockState()), MIN_SECTION_Y, MAX_SECTION_Y);
        return new Fixture(new MinecraftProjectionWorldView(runtime, level, sections), runtime, level, chunks, leases, lease, ready, projections);
    }

    private record Fixture(MinecraftProjectionWorldView view, WormholesModRuntime runtime, ServerLevel level, ServerChunkCache chunks,
                           ChunkLeaseRegistry<ServerLevel> leases, ChunkLease lease, CompletableFuture<Boolean> ready,
                           MinecraftProjectionService projections) {
    }
}
