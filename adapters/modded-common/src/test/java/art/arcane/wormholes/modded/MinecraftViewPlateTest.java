package art.arcane.wormholes.modded;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.plate.PlateCaptureJob;
import art.arcane.optics.plate.PlateCell;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateBuilder;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftViewPlateTest extends MinecraftTestBase {
    @Test
    public void meshSectionsKeepDestinationModelStatesWhilePacketPlatesRotateStates() {
        BlockState source = Blocks.OAK_STAIRS.defaultBlockState();
        ContentView<BlockState, BlockState> view = mock(ContentView.class);
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(source);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(source);
        when(view.worldId()).thenReturn(UUID.randomUUID());
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenReturn("minecraft:plains");
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ContentView.packLight(15, 0));
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(0, 0.999D, 64, 64.999D, 0, 0.999D));
        ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), view, true, 0, 0L);
        ViewPlateBuilder.Request<BlockState, BlockState, ContentView<BlockState, BlockState>> request = new ViewPlateBuilder.Request<>(
            key, geometry, view, Frame.canonical(Face.E), Frame.canonical(Face.S),
            0.4995D, 64.4995D, 0.4995D, 20.4995D, 64.4995D, 20.4995D, false, 0,
            4, 0, 0, false, Blocks.AIR.defaultBlockState(), LodPolicy.NONE, false, 0L, 0L, 0L, MinecraftProjectorBlocks.INSTANCE);
        ViewPlateBuilder.Job<BlockState, Object> section = ViewPlateBuilder.sectionJob(request, new PlateBox(-16, 64, 0, 16, 16, 16));
        assertTrue(section.step(Integer.MAX_VALUE));
        long cell = CellKeys.pack(-1, 64, 0);
        assertSame(source, section.result().cell(cell).data());
        assertSame(source, section.result().cell(cell).sourceData());
        assertNotEquals(source, ViewPlateBuilder.build(request).cell(cell).data());
    }

    @Test
    public void localPlatesAreCapturedOnTheServerThreadAndBuiltOffItWithEveryCellIntact() throws InterruptedException {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        when(runtime.server()).thenReturn(server);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(MinecraftTestSettings.defaults());
        BlockState stone = Blocks.STONE.defaultBlockState();
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        when(level.getChunkSource()).thenReturn(chunks);
        PalettedContainer<BlockState> states = MinecraftPlateCaptureSourceTest.container();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    states.set(x, y, z, stone);
                }
            }
        }
        LevelChunkSection solid = mock(LevelChunkSection.class);
        when(solid.hasOnlyAir()).thenReturn(false);
        when(solid.getStates()).thenReturn(states);
        LevelChunk chunk = mock(LevelChunk.class);
        when(chunk.getSectionsCount()).thenReturn(24);
        when(chunk.getMinSectionY()).thenReturn(-4);
        when(chunk.getSection(anyInt())).thenReturn(solid);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
        MinecraftProjectionWorldView view = mock(MinecraftProjectionWorldView.class);
        UUID worldId = UUID.randomUUID();
        when(view.worldId()).thenReturn(worldId);
        when(view.getWorld()).thenReturn(level);
        when(view.getMinHeight()).thenReturn(-64);
        when(view.getMaxHeight()).thenReturn(320);
        when(view.sampleBlockData(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        when(view.sampleMaterial(anyInt(), anyInt(), anyInt())).thenReturn(stone);
        ApertureCells geometry = new ApertureCells();
        geometry.setArea(new Box(0, 2, 64, 67, 0, 1));
        Frame frame = Frame.canonical(Face.S);
        ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), view, true, 0, 0L);
        ViewPlateBuilder.Request<BlockState, BlockState, ContentView<BlockState, BlockState>> request = new ViewPlateBuilder.Request<>(
            key, geometry, view, frame, frame,
            1.0D, 65.0D, 0.0D, 1.0D, 65.0D, 0.0D, false, 0,
            4.0D, 0.0D, 0.0D, false, Blocks.AIR.defaultBlockState(), LodPolicy.NONE,
            false, 7L, 3L, 2L, MinecraftProjectorBlocks.INSTANCE);
        ViewPlate<BlockState> live = ViewPlateBuilder.build(request);
        assertFalse(live.isEmpty());
        clearInvocations(view);
        MinecraftProjectionService service = new MinecraftProjectionService(runtime);
        when(runtime.projections()).thenReturn(service);
        service.start();
        try {
            PlateCaptureJob<BlockState, ServerLevel, MinecraftPlateCaptureSource.CapturedChunk> job = new PlateCaptureJob<>(new PlateCaptureJob.Plan<>(
                key, level, ViewPlateBuilder.footprint(request), new MinecraftPlateCaptureSource(runtime, MinecraftPlateCaptureSource.Options.column(worldId, false)),
                captured -> ViewPlateBuilder.job(request.withDestView(new MinecraftCapturedChunkView(worldId, -64, 320, 7L, captured)))));
            assertNull(service.plates().current(key, 7L, 3L, new WorldChangeTracker(), false, previous -> job));
            assertEquals(1, service.plateCaptureQueueSize());
            assertTrue(service.plates().isBuilding(job));
            int ticks = 0;
            while (service.plateCaptureQueueSize() > 0) {
                assertTrue("capture never drained", ++ticks < 64);
                service.tick();
            }
            assertEquals(PlateCaptureJob.Phase.CAPTURED, job.phase());
            ViewPlate<BlockState> built = waitFor(() -> service.plates().peek(key));
            assertEquals(live.cellKeys(), built.cellKeys());
            assertEquals(worldId, built.destinationWorldId());
            assertEquals(7L, built.destinationRevision());
            for (long cell : live.cellKeys()) {
                PlateCell<BlockState> expected = live.cell(cell);
                PlateCell<BlockState> actual = built.cell(cell);
                assertEquals(ProjectorSample.Kind.BLOCK, actual.kind());
                assertSame(expected.data(), actual.data());
                assertSame(expected.sourceData(), actual.sourceData());
            }
            verify(runtime, never()).schedule(any(), anyLong());
            verify(view, never()).sampleBlockData(anyInt(), anyInt(), anyInt());
        } finally {
            service.close();
        }
    }

    private static ViewPlate<BlockState> waitFor(Supplier<ViewPlate<BlockState>> plate) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            ViewPlate<BlockState> current = plate.get();
            if (current != null) {
                return current;
            }
            Thread.sleep(10L);
        }
        assertNotNull("plate build did not publish within 5 seconds", plate.get());
        return plate.get();
    }
}
