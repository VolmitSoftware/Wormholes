package art.arcane.wormholes.modded;

import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftProjectionWorldViewTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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
    public void worldEditsAndReadinessInvalidateWhileIdleTicksPreserveSharedProofs() {
        Fixture fixture = fixture();
        try (MinecraftProjectionWorldView view = fixture.view()) {
            long initial = view.getRevision();
            when(fixture.level().getGameTime()).thenReturn(1L);
            assertEquals(initial, view.getRevision());
            view.invalidate();
            long tick = view.getRevision();
            assertTrue(tick > initial);
            view.requestChunk(0, 0);
            fixture.ready().complete(true);
            assertTrue(view.getRevision() > tick);
        }
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        ServerLevel level = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        MinecraftServer server = mock(MinecraftServer.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        ChunkLease lease = mock(ChunkLease.class);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        when(runtime.server()).thenReturn(server);
        when(runtime.leases()).thenReturn(leases);
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig()));
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
        return new Fixture(new MinecraftProjectionWorldView(runtime, level), level, chunks, leases, lease, ready);
    }

    private record Fixture(MinecraftProjectionWorldView view, ServerLevel level, ServerChunkCache chunks,
                           ChunkLeaseRegistry<ServerLevel> leases, ChunkLease lease, CompletableFuture<Boolean> ready) {
    }
}
