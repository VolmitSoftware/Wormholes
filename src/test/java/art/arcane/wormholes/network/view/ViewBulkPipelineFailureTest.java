package art.arcane.wormholes.network.view;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.network.NetworkManager;
import art.arcane.wormholes.network.replication.ChunkBulkBuilder;
import art.arcane.wormholes.network.replication.ChunkReplicationManager;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.wormholes.render.blockentity.BlockEntityCapturer;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.MockedConstruction;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ViewBulkPipelineFailureTest {
    @Test
    void failedSnapshotRetriesAndLogsTheCauseOnce() {
        verifyFailure(new IllegalStateException("snapshot unavailable"), FailureStage.SNAPSHOT);
    }

    @Test
    void failedEncodingRetriesAndLogsTheCauseOnce() {
        verifyFailure(new IOException("encoding unavailable"), FailureStage.ENCODING);
    }

    @Test
    void failedChunkLoadingRetriesAndLogsTheCauseOnce() {
        verifyFailure(new IllegalStateException("loading unavailable"), FailureStage.LOADING);
    }

    @Test
    void failedCaptureSchedulingRetriesAndLogsTheCauseOnce() {
        verifyFailure(new IllegalStateException("scheduling unavailable"), FailureStage.SCHEDULING);
    }

    @Test
    void failedLoggingDoesNotLeaveBulkPending() {
        verifyFailure(new IllegalStateException("snapshot unavailable"), FailureStage.LOGGING);
    }

    @Test
    void asynchronousChunkLoadingFailureRetriesAndLogsTheCauseOnce() {
        verifyFailure(new IllegalStateException("loading unavailable"), FailureStage.ASYNC_LOADING);
    }

    private void verifyFailure(Throwable failure, FailureStage stage) {
        Wormholes previous = Wormholes.instance;
        Wormholes plugin = mock(Wormholes.class);
        Logger logger = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(logger);
        if (stage == FailureStage.LOGGING) {
            doThrow(new IllegalStateException("logger unavailable")).when(logger)
                .log(any(Level.class), anyString(), any(Throwable.class));
        }
        Wormholes.instance = plugin;
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        ViewSession session = new ViewSession(UUID.randomUUID(), world, new ViewBox(0, 64, 0, 0, 64, 0),
            ProjectionRenderMode.PANOPTIC, 0, 0, 0, 64, 0);
        ViewSessionRegistry registry = mock(ViewSessionRegistry.class);
        NetworkManager network = mock(NetworkManager.class);
        ChunkReplicationManager replication = mock(ChunkReplicationManager.class);
        when(registry.network()).thenReturn(network);
        when(registry.replication()).thenReturn(replication);
        when(registry.isSessionChunkActive(eq(session), eq("peer"), anyLong())).thenReturn(true);
        when(replication.bulkGeneration(eq("peer"), any())).thenReturn(0L);
        Queue<Runnable> retries = new ArrayDeque<>();
        Chunk chunk = mock(Chunk.class);
        ChunkSnapshot snapshot = mock(ChunkSnapshot.class);
        ViewSlice slice = new ViewSlice(0, 64, 0, 1, 1, 1, List.of("minecraft:stone"),
            new short[1], new byte[1], List.of("minecraft:plains"), new short[1]);
        try (MockedStatic<WormholesPlatform> platform = mockStatic(WormholesPlatform.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
             MockedStatic<BlockEntityCapturer> entities = mockStatic(BlockEntityCapturer.class);
             MockedStatic<?> encoder = mockStatic(ChunkBulkBuilder.class);
             MockedConstruction<?> builders = mockConstruction(ChunkBulkBuilder.class, (builder, context) ->
                 when(builder.buildSlice(any(ViewBox.class), eq(0), eq(0), any(), any(ProjectionRenderMode.class), any(Map.class)))
                     .thenReturn(slice))) {
            platform.when(() -> WormholesPlatform.loadChunk(plugin, world, 0, 0))
                .thenReturn(CompletableFuture.completedFuture(chunk));
            if (stage == FailureStage.ENCODING) {
                platform.when(() -> WormholesPlatform.chunkSnapshot(chunk, false, true, false, true)).thenReturn(snapshot);
                entities.when(() -> BlockEntityCapturer.captureChunk(eq(chunk), any())).thenReturn(Map.of());
                encoder.when(() -> ChunkBulkBuilder.encodeSliceBytes(any(ViewSlice.class), anyBoolean())).thenThrow(failure);
            } else {
                platform.when(() -> WormholesPlatform.chunkSnapshot(chunk, false, true, false, true)).thenThrow(failure);
            }
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), eq(world), eq(0), eq(0), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(4, Runnable.class).run();
                    return true;
                });
            scheduler.when(() -> FoliaScheduler.runAsync(eq(plugin), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return true;
                });
            if (stage == FailureStage.LOADING) {
                platform.when(() -> WormholesPlatform.loadChunk(plugin, world, 0, 0)).thenThrow(failure);
            } else if (stage == FailureStage.ASYNC_LOADING) {
                platform.when(() -> WormholesPlatform.loadChunk(plugin, world, 0, 0))
                    .thenReturn(CompletableFuture.failedFuture(failure));
            } else if (stage == FailureStage.SCHEDULING) {
                scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), eq(world), eq(0), eq(0), any(Runnable.class)))
                    .thenThrow(failure);
            }
            scheduler.when(() -> FoliaScheduler.runAsync(eq(plugin), any(Runnable.class), anyLong()))
                .thenAnswer(invocation -> {
                    retries.add(invocation.getArgument(1, Runnable.class));
                    return true;
                });
            ViewBulkPipeline pipeline = new ViewBulkPipeline(registry, mock(ViewTimeDelivery.class));
            CompletableFuture<Boolean> result = pipeline.sendInitialBulkWithRetry(session, "peer", 0, 0);
            assertFalse(result.isDone());
            assertFalse(retries.isEmpty());
            retries.remove().run();
            assertFalse(retries.isEmpty());
            ArgumentCaptor<Throwable> logged = ArgumentCaptor.forClass(Throwable.class);
            verify(logger, times(1)).log(eq(Level.WARNING), contains(session.portalId.toString()), logged.capture());
            assertTrue(logged.getValue() == failure || logged.getValue().getCause() == failure);
            when(registry.isSessionChunkActive(eq(session), eq("peer"), anyLong())).thenReturn(false);
            retries.remove().run();
            assertTrue(result.isDone());
            assertFalse(result.join());
        } finally {
            Wormholes.instance = previous;
        }
    }
    private enum FailureStage {
        SNAPSHOT,
        ENCODING,
        LOADING,
        SCHEDULING,
        LOGGING,
        ASYNC_LOADING
    }
}
