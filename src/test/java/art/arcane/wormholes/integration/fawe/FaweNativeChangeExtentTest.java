package art.arcane.wormholes.integration.fawe;

import art.arcane.optics.view.WorldChangeTracker;
import com.fastasyncworldedit.core.util.task.NativeChangeScope;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.function.operation.RunContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockState;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FaweNativeChangeExtentTest {
    @Test
    void publishesSynchronousWritesWithoutACommitAndCoalescesChunks() throws WorldEditException {
        Fixture fixture = new Fixture();
        BiomeType biome = mock(BiomeType.class);
        when(fixture.world.setBlock(-1, 64, 32, fixture.block)).thenReturn(true);
        when(fixture.world.setBiome(17, 64, 32, biome)).thenReturn(true);

        assertTrue(fixture.extent.setBlock(fixture.position, fixture.block));
        assertTrue(fixture.extent.setBlock(-1, 64, 32, fixture.block));
        assertTrue(fixture.extent.setBiome(17, 64, 32, biome));
        assertSame(fixture.block, fixture.blocks.get(fixture.position));
        assertEquals(1, fixture.scheduled.size());
        fixture.drain();
        assertEquals(3L, fixture.tracker.currentVersion());
        assertTrue(fixture.tracker.dirtySince(fixture.worldId, 0, 0, 0, 0, 0L));
        assertTrue(fixture.tracker.dirtySince(fixture.worldId, -1, 2, -1, 2, 0L));
        assertTrue(fixture.tracker.dirtySince(fixture.worldId, 1, 2, 1, 2, 0L));
    }

    @Test
    void cancellationDoesNotDiscardAlreadyAppliedWrites() throws WorldEditException {
        Fixture fixture = new Fixture();
        fixture.extent.setBlock(fixture.position, fixture.block);
        fixture.extent.commit().cancel();
        fixture.drain();
        assertSame(fixture.block, fixture.blocks.get(fixture.position));
        assertTrue(fixture.tracker.dirtySince(fixture.worldId, 0, 0, 0, 0, 0L));
    }

    @Test
    void editSessionExtentCancellationKeepsAppliedWritesAndStopsFutureWrites() throws WorldEditException {
        Fixture fixture = new Fixture();
        fixture.extent.setBlock(fixture.position, fixture.block);
        AbstractDelegateExtent session = new AbstractDelegateExtent(fixture.extent) { };
        assertTrue(session.cancel());
        fixture.drain();
        assertSame(fixture.block, fixture.blocks.get(fixture.position));
        assertTrue(fixture.tracker.dirtySince(fixture.worldId, 0, 0, 0, 0, 0L));
        assertThrows(RuntimeException.class, () -> session.setBlock(BlockVector3.at(17, 64, 1), fixture.block));
        assertEquals(1, fixture.blocks.size());
    }

    @Test
    void detachedNativeApplicationRemainsObservedAfterCommitCancellation() throws WorldEditException {
        Fixture fixture = new Fixture();
        AtomicReference<NativeChangeScope> admitted = new AtomicReference<>();
        doAnswer(invocation -> {
            admitted.set(NativeChangeScope.capture());
            return true;
        }).when(fixture.world).setBlock(fixture.position, fixture.block);
        assertTrue(fixture.blocks.isEmpty());
        fixture.extent.setBlock(fixture.position, fixture.block);
        fixture.extent.commit().cancel();
        assertTrue(fixture.scheduled.isEmpty());
        assertTrue(fixture.blocks.isEmpty());

        fixture.blocks.put(fixture.position, fixture.block);
        admitted.get().applied(true);
        fixture.drain();
        assertTrue(fixture.tracker.dirtySince(fixture.worldId, 0, 0, 0, 0, 0L));
        admitted.get().applied(true);
        assertTrue(fixture.scheduled.isEmpty());
    }

    @Test
    void waitsForDeferredNativeApplicationAfterSessionCloses() throws WorldEditException {
        Fixture fixture = new Fixture();
        FaweEditQuiescence quiescence = new FaweEditQuiescence();
        FaweEditQuiescence.Session session = quiescence.admit();
        FaweAppliedChunkProcessor processor = new FaweAppliedChunkProcessor(new FaweAppliedChunkProcessor.Binding(
                fixture.worldId, 0L, fixture.invalidator, quiescence, session));
        Extent extent = processor.construct(fixture.world);
        AtomicReference<NativeChangeScope> admitted = new AtomicReference<>();
        doAnswer(invocation -> {
            admitted.set(NativeChangeScope.capture());
            return true;
        }).when(fixture.world).setBlock(fixture.position, fixture.block);
        assertTrue(fixture.blocks.isEmpty());
        extent.setBlock(fixture.position, fixture.block);
        CompletableFuture<Void> drained = quiescence.prepare();
        session.close();
        assertFalse(drained.isDone());
        fixture.blocks.put(fixture.position, fixture.block);
        admitted.get().applied(true);
        assertTrue(drained.isDone());
        fixture.drain();
        assertTrue(fixture.tracker.dirtySince(fixture.worldId, 0, 0, 0, 0, 0L));
        assertThrows(IllegalStateException.class, () -> extent.setBlock(fixture.position, fixture.block));
    }

    @Test
    void doesNotNotifyFailedWritesAndPreservesChildCommitBeforeFlush() throws WorldEditException {
        Fixture fixture = new Fixture();
        doReturn(false).when(fixture.world).setBlock(fixture.position, fixture.block);
        assertFalse(fixture.extent.setBlock(fixture.position, fixture.block));
        assertTrue(fixture.blocks.isEmpty());
        assertTrue(fixture.scheduled.isEmpty());
        doThrow(new IllegalStateException("Native write failed")).when(fixture.world)
                .setBlock(fixture.position, fixture.block);
        assertThrows(IllegalStateException.class, () -> fixture.extent.setBlock(fixture.position, fixture.block));
        assertTrue(fixture.scheduled.isEmpty());

        AtomicBoolean committed = new AtomicBoolean();
        Operation child = new Operation() {
            @Override
            public Operation resume(RunContext run) {
                committed.set(true);
                return null;
            }

            @Override
            public void cancel() {
            }
        };
        when(fixture.world.commit()).thenReturn(child);
        doAnswer(invocation -> {
            assertTrue(committed.get());
            return null;
        }).when(fixture.world).flush();
        Operations.completeBlindly(fixture.extent.commit());
        verify(fixture.world).flush();
        assertTrue(fixture.scheduled.isEmpty());
    }

    @Test
    void fullShutdownDropsLateCompletions() throws WorldEditException {
        Fixture fixture = new Fixture();
        AtomicReference<NativeChangeScope> admitted = new AtomicReference<>();
        doAnswer(invocation -> {
            admitted.set(NativeChangeScope.capture());
            return true;
        }).when(fixture.world).setBlock(fixture.position, fixture.block);
        assertTrue(fixture.blocks.isEmpty());
        fixture.extent.setBlock(fixture.position, fixture.block);
        fixture.invalidator.close();
        admitted.get().applied(true);
        fixture.drain();
        assertEquals(0L, fixture.tracker.currentVersion());
    }

    private static final class Fixture {
        private final WorldChangeTracker tracker = new WorldChangeTracker();
        private final Queue<Runnable> scheduled = new ArrayDeque<>();
        private final UUID worldId = UUID.randomUUID();
        private final FaweAppliedChunkInvalidator invalidator = new FaweAppliedChunkInvalidator(tracker, scheduled::add);
        private final World world = mock(World.class);
        private final BlockState block = mock(BlockState.class);
        private final BlockVector3 position = BlockVector3.at(1, 64, 1);
        private final Map<BlockVector3, BlockState> blocks = new HashMap<>();
        private final Extent extent = new FaweAppliedChunkProcessor(worldId, invalidator).construct(world);

        private Fixture() throws WorldEditException {
            when(world.setBlock(position, block)).thenAnswer(invocation -> {
                blocks.put(position, block);
                return true;
            });
        }

        private void drain() {
            while (!scheduled.isEmpty()) {
                scheduled.remove().run();
            }
        }
    }
}
