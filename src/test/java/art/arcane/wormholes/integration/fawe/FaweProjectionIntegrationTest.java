package art.arcane.wormholes.integration.fawe;

import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.optics.view.WorldChangeListener;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.extent.processor.BatchProcessorHolder;
import com.fastasyncworldedit.core.extent.processor.ProcessorScope;
import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkSet;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.eventbus.EventBus;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FaweProjectionIntegrationTest {
    @Test
    void preservesExistingProcessorsAndExtentAtQueueCompletion() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            BatchProcessorHolder chain = new BatchProcessorHolder();
            IBatchProcessor existing = mock(IBatchProcessor.class);
            IBatchProcessor preprocessing = mock(IBatchProcessor.class);
            when(existing.getScope()).thenReturn(ProcessorScope.READING_BLOCKS);
            when(existing.joinPost(any())).thenCallRealMethod();
            chain.setProcessor(preprocessing);
            chain.setPostProcessor(existing);
            Extent extent = queue(chain);
            EditSessionEvent event = event(extent, EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(event);
            assertSame(extent, event.getExtent());
            assertSame(preprocessing, chain.getProcessor());
            assertEquals(0L, fixture.tracker.currentVersion());
            assertTrue(fixture.scheduled.isEmpty());

            IChunk chunk = mock(IChunk.class);
            when(chunk.getX()).thenReturn(3);
            when(chunk.getZ()).thenReturn(-5);
            IChunkSet set = mock(IChunkSet.class);
            chain.postProcess(chunk, null, set);
            verify(existing, times(1)).postProcess(chunk, null, set);
            assertEquals(0L, fixture.tracker.currentVersion());
            fixture.scheduled.remove().run();
            assertEquals(1L, fixture.tracker.currentVersion());
            assertTrue(fixture.tracker.dirtySince(fixture.worldId, 3, -5, 3, -5, 0L));

            CompletableFuture<Void> history = new CompletableFuture<>();
            when(existing.postProcessSet(chunk, null, set)).thenAnswer(ignored -> history);
            Future<?> completion = chain.postProcessSet(chunk, null, set);
            assertFalse(completion.isDone());
            history.complete(null);
            assertTrue(completion.isDone());
            verify(existing, times(1)).postProcessSet(chunk, null, set);
            fixture.scheduled.remove().run();
            assertEquals(2L, fixture.tracker.currentVersion());

            fixture.integration.close();
            chain.postProcess(chunk, null, set);
            verify(existing, times(2)).postProcess(chunk, null, set);
            assertTrue(fixture.scheduled.isEmpty());
        }
    }

    @Test
    void attachesOnlyBeforeChangeInLoadedUncancelledWorlds() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            Extent extent = queue(new BatchProcessorHolder());
            fixture.bus.post(event(extent, EditSession.Stage.BEFORE_HISTORY));
            fixture.bus.post(event(extent, EditSession.Stage.BEFORE_REORDER));
            EditSessionEvent cancelled = event(extent, EditSession.Stage.BEFORE_CHANGE);
            cancelled.setCancelled(true);
            fixture.bus.post(cancelled);
            EditSessionEvent noWorld = new EditSessionEvent(null, null, -1, EditSession.Stage.BEFORE_CHANGE);
            noWorld.setExtent(extent);
            fixture.bus.post(noWorld);
            fixture.integration.onWorldUnload(new WorldUnloadEvent(fixture.world));
            fixture.bus.post(event(extent, EditSession.Stage.BEFORE_CHANGE));
            verify(extent, never()).addPostProcessor(any());

            fixture.integration.onWorldLoad(new WorldLoadEvent(fixture.world));
            fixture.bus.post(event(extent, EditSession.Stage.BEFORE_CHANGE));
            verify(extent, times(1)).addPostProcessor(any(FaweAppliedChunkProcessor.class));
            fixture.integration.close();
            fixture.bus.post(event(extent, EditSession.Stage.BEFORE_CHANGE));
            verify(extent, times(1)).addPostProcessor(any());
        }
    }

    @Test
    void wrapsNativeExtentAndRemovesOnlyOwnedAllowance() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            Extent child = mock(Extent.class);
            when(child.addPostProcessor(any())).thenAnswer(invocation -> {
                IBatchProcessor processor = invocation.getArgument(0);
                return processor.construct(child);
            });
            EditSessionEvent event = event(child, EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(event);
            AbstractDelegateExtent wrapped = assertInstanceOf(FaweNativeChangeExtent.class, event.getExtent());
            assertSame(child, wrapped.getExtent());
            assertTrue(Settings.settings().EXTENT.ALLOWED_PLUGINS.contains(FaweNativeChangeExtent.class.getName()));
            String externalCopy = new String(FaweNativeChangeExtent.class.getName());
            Settings.settings().EXTENT.ALLOWED_PLUGINS.add(externalCopy);
            Settings.settings().EXTENT.ALLOWED_PLUGINS.add("later.integration");
            fixture.integration.close();
            assertEquals(List.of("existing.integration", externalCopy, "later.integration"),
                    Settings.settings().EXTENT.ALLOWED_PLUGINS);
        }
    }

    @Test
    void leavesPreexistingNativeAllowanceUntouched() {
        List<String> allowed = List.of("existing.integration", "ART.ARCANE.WORMHOLES");
        try (Fixture fixture = new Fixture(allowed)) {
            assertEquals(allowed, Settings.settings().EXTENT.ALLOWED_PLUGINS);
            fixture.integration.close();
            assertEquals(allowed, Settings.settings().EXTENT.ALLOWED_PLUGINS);
        }
    }

    @Test
    void restoresNativeAllowanceBeforePublishingAfterSettingsReload() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            String owned = Settings.settings().EXTENT.ALLOWED_PLUGINS.getLast();
            Settings.settings().EXTENT.ALLOWED_PLUGINS = new ArrayList<>(List.of("reloaded.integration"));
            assertFalse(Settings.settings().EXTENT.ALLOWED_PLUGINS.contains(FaweNativeChangeExtent.class.getName()));
            Extent child = nativeExtent();
            EditSessionEvent event = spy(event(child, EditSession.Stage.BEFORE_CHANGE));
            doAnswer(invocation -> {
                assertEquals(List.of("reloaded.integration", owned), Settings.settings().EXTENT.ALLOWED_PLUGINS);
                assertSame(owned, Settings.settings().EXTENT.ALLOWED_PLUGINS.getLast());
                return invocation.callRealMethod();
            }).when(event).setExtent(any());

            fixture.integration.onEditSession(event);

            AbstractDelegateExtent wrapped = assertInstanceOf(FaweNativeChangeExtent.class, event.getExtent());
            assertSame(child, wrapped.getExtent());
            fixture.integration.onEditSession(event(nativeExtent(), EditSession.Stage.BEFORE_CHANGE));
            assertEquals(List.of("reloaded.integration", owned), Settings.settings().EXTENT.ALLOWED_PLUGINS);
            fixture.integration.close();
            assertEquals(List.of("reloaded.integration"), Settings.settings().EXTENT.ALLOWED_PLUGINS);
        }
    }

    @Test
    void preservesUserNativeAllowanceAfterSettingsReload() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            String userAllowance = new String(FaweNativeChangeExtent.class.getName());
            List<String> reloaded = new ArrayList<>(List.of("reloaded.integration", userAllowance));
            Settings.settings().EXTENT.ALLOWED_PLUGINS = reloaded;
            EditSessionEvent event = event(nativeExtent(), EditSession.Stage.BEFORE_CHANGE);

            fixture.integration.onEditSession(event);

            assertInstanceOf(FaweNativeChangeExtent.class, event.getExtent());
            assertSame(reloaded, Settings.settings().EXTENT.ALLOWED_PLUGINS);
            assertSame(userAllowance, Settings.settings().EXTENT.ALLOWED_PLUGINS.getLast());
            fixture.integration.close();
            assertEquals(List.of("reloaded.integration", userAllowance), Settings.settings().EXTENT.ALLOWED_PLUGINS);
            assertSame(userAllowance, Settings.settings().EXTENT.ALLOWED_PLUGINS.getLast());
        }
    }

    @Test
    void restoresOnlyOneOwnedAllowanceForConcurrentNativeSessions() throws Exception {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            String owned = Settings.settings().EXTENT.ALLOWED_PLUGINS.getLast();
            Settings.settings().EXTENT.ALLOWED_PLUGINS = new ArrayList<>(List.of("reloaded.integration"));
            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch ready = new CountDownLatch(4);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<EditSessionEvent>> sessions = new ArrayList<>(4);
            try {
                for (int worker = 0; worker < 4; worker++) {
                    sessions.add(executor.submit(() -> {
                        EditSessionEvent event = event(nativeExtent(), EditSession.Stage.BEFORE_CHANGE);
                        ready.countDown();
                        assertTrue(start.await(5L, TimeUnit.SECONDS));
                        fixture.integration.onEditSession(event);
                        return event;
                    }));
                }
                assertTrue(ready.await(5L, TimeUnit.SECONDS));
                start.countDown();
                for (Future<EditSessionEvent> session : sessions) {
                    assertInstanceOf(FaweNativeChangeExtent.class, session.get(5L, TimeUnit.SECONDS).getExtent());
                }
                assertEquals(List.of("reloaded.integration", owned), Settings.settings().EXTENT.ALLOWED_PLUGINS);
                assertSame(owned, Settings.settings().EXTENT.ALLOWED_PLUGINS.getLast());
                fixture.integration.close();
                assertEquals(List.of("reloaded.integration"), Settings.settings().EXTENT.ALLOWED_PLUGINS);
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5L, TimeUnit.SECONDS));
            }
        }
    }

    private static Extent nativeExtent() {
        Extent extent = mock(Extent.class);
        when(extent.addPostProcessor(any())).thenCallRealMethod();
        return extent;
    }

    @Test
    void detectsOptionalCompletionHooks() {
        assertEquals(FaweProjectionIntegration.Mode.PATCHED, FaweProjectionIntegration.detectMode());
        assertEquals(FaweProjectionIntegration.Mode.STANDARD,
                FaweProjectionIntegration.detectMode(new MissingNativeScopeLoader()));
    }

    @Test
    void unsupportedNativeAdapterPreservesQueuedTrackingAndSessionCompletion() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            BatchProcessorHolder chain = new BatchProcessorHolder();
            EditSessionEvent queued = event(queue(chain), EditSession.Stage.BEFORE_CHANGE);
            BukkitWorld queuedWorld = assertInstanceOf(BukkitWorld.class, queued.getWorld());
            when(queuedWorld.appliedChangeSupportVersion()).thenReturn(0);
            fixture.integration.onEditSession(queued);
            IChunk chunk = mock(IChunk.class);
            when(chunk.getX()).thenReturn(3);
            when(chunk.getZ()).thenReturn(-5);
            chain.postProcess(chunk, null, mock(IChunkSet.class));
            fixture.scheduled.remove().run();
            assertTrue(fixture.tracker.dirtySince(fixture.worldId, 3, -5, 3, -5, 0L));

            Extent child = nativeExtent();
            EditSessionEvent nativeEdit = event(child, EditSession.Stage.BEFORE_CHANGE);
            BukkitWorld nativeWorld = assertInstanceOf(BukkitWorld.class, nativeEdit.getWorld());
            when(nativeWorld.appliedChangeSupportVersion()).thenReturn(0);
            fixture.integration.onEditSession(nativeEdit);
            assertSame(child, nativeEdit.getExtent());
            assertFalse(nativeEdit.isCancelled());
            assertTrue(fixture.integration.supportsReload());

            CompletableFuture<Void> prepared = fixture.integration.prepareReload(Duration.ofSeconds(1L)).toCompletableFuture();
            assertFalse(prepared.isDone());
            queued.completeSession();
            assertFalse(prepared.isDone());
            nativeEdit.completeSession();
            fixture.scheduled.remove().run();
            prepared.join();
        }
    }

    @Test
    void standardModeRefreshesQueuedEditsWithoutPatchedLifecycleOrNativeTracking() {
        List<String> allowed = List.of("existing.integration");
        try (Fixture fixture = new Fixture(allowed, FaweProjectionIntegration.Mode.STANDARD)) {
            BatchProcessorHolder chain = new BatchProcessorHolder();
            IBatchProcessor existing = mock(IBatchProcessor.class);
            when(existing.getScope()).thenReturn(ProcessorScope.READING_BLOCKS);
            when(existing.joinPost(any())).thenCallRealMethod();
            chain.setPostProcessor(existing);
            Extent extent = queue(chain);
            EditSessionEvent queued = spy(event(extent, EditSession.Stage.BEFORE_CHANGE));
            fixture.integration.onEditSession(queued);
            verify(queued, never()).onSessionClose(any());
            assertSame(extent, queued.getExtent());
            assertFalse(queued.isCancelled());
            assertTrue(fixture.scheduled.isEmpty());

            IChunk chunk = mock(IChunk.class);
            when(chunk.getX()).thenReturn(3);
            when(chunk.getZ()).thenReturn(-5);
            IChunkSet set = mock(IChunkSet.class);
            chain.postProcess(chunk, null, set);
            verify(existing).postProcess(chunk, null, set);
            assertEquals(0L, fixture.tracker.currentVersion());
            fixture.scheduled.remove().run();
            assertTrue(fixture.tracker.dirtySince(fixture.worldId, 3, -5, 3, -5, 0L));

            Extent child = nativeExtent();
            EditSessionEvent nativeEdit = spy(event(child, EditSession.Stage.BEFORE_CHANGE));
            fixture.integration.onEditSession(nativeEdit);
            assertSame(child, nativeEdit.getExtent());
            verify(nativeEdit, never()).onSessionClose(any());
            verify(nativeEdit, never()).setExtent(any());
            assertEquals(allowed, Settings.settings().EXTENT.ALLOWED_PLUGINS);
            fixture.integration.close();
            chain.postProcess(chunk, null, set);
            assertTrue(fixture.scheduled.isEmpty());
            assertEquals(allowed, Settings.settings().EXTENT.ALLOWED_PLUGINS);
        }
    }

    @Test
    void standardReloadRefusalLeavesEditAdmissionOpen() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"), FaweProjectionIntegration.Mode.STANDARD)) {
            assertFalse(fixture.integration.supportsReload());
            CompletionException refusal = assertThrows(CompletionException.class,
                    () -> fixture.integration.prepareReload(Duration.ofSeconds(1L)).toCompletableFuture().join());
            assertInstanceOf(IllegalStateException.class, refusal.getCause());
            assertTrue(fixture.scheduled.isEmpty());
            BatchProcessorHolder chain = new BatchProcessorHolder();
            EditSessionEvent event = event(queue(chain), EditSession.Stage.BEFORE_CHANGE);
            fixture.integration.onEditSession(event);
            assertFalse(event.isCancelled());
            IChunk chunk = mock(IChunk.class);
            chain.postProcess(chunk, null, mock(IChunkSet.class));
            fixture.scheduled.remove().run();
            assertTrue(fixture.tracker.dirtySince(fixture.worldId, 0, 0, 0, 0, 0L));
        }
    }

    @Test
    void runtimeTrackingFailureDoesNotAbortTheEdit() {
        assertTrackingFailureIsContained(new IllegalStateException("processor registration failed"));
    }

    @Test
    void incompatibleTrackingApiDoesNotAbortTheEdit() {
        assertTrackingFailureIsContained(new NoSuchMethodError("processor API changed"));
    }

    private static void assertTrackingFailureIsContained(Throwable failure) {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            Extent failing = mock(Extent.class);
            doThrow(failure).when(failing).addPostProcessor(any());
            EditSessionEvent event = event(failing, EditSession.Stage.BEFORE_CHANGE);
            assertDoesNotThrow(() -> fixture.integration.onEditSession(event));
            assertFalse(event.isCancelled());
            assertSame(failing, event.getExtent());
            event.completeSession();
            assertEquals(List.of("existing.integration"), Settings.settings().EXTENT.ALLOWED_PLUGINS);

            Extent later = queue(new BatchProcessorHolder());
            fixture.bus.post(event(later, EditSession.Stage.BEFORE_CHANGE));
            fixture.integration.onEditSession(event(later, EditSession.Stage.BEFORE_CHANGE));
            verify(later, never()).addPostProcessor(any());
            verify(fixture.logger, times(1)).log(eq(Level.WARNING), anyString(), same(failure));
            assertTrue(fixture.scheduled.isEmpty());
        }
    }

    @Test
    void reloadWaitsForSessionAndAdmittedApplicationAndDrainsCurrentTracker() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            Thread owner = Thread.currentThread();
            WorldChangeListener listener = mock(WorldChangeListener.class);
            doAnswer(invocation -> {
                assertSame(owner, Thread.currentThread());
                return null;
            }).when(listener).columnChanged(fixture.worldId, 3, -5);
            fixture.tracker.addListener(listener);
            BatchProcessorHolder chain = new BatchProcessorHolder();
            EditSessionEvent original = event(queue(chain), EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(original);
            IChunk chunk = mock(IChunk.class);
            when(chunk.getX()).thenReturn(3);
            when(chunk.getZ()).thenReturn(-5);
            IBatchProcessor completion = chain.capturePostProcessor(chunk);
            completion.applicationStarted();
            CompletionStage<Void> reload = fixture.integration.prepareReload(Duration.ofSeconds(5L));
            assertFalse(reload.toCompletableFuture().isDone());
            EditSessionEvent blocked = event(queue(new BatchProcessorHolder()), EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(blocked);
            assertTrue(blocked.isCancelled());

            original.completeSession();
            assertFalse(reload.toCompletableFuture().isDone());
            when(chunk.getX()).thenReturn(50);
            completion.postProcess(chunk, null, mock(IChunkSet.class));
            assertEquals(0L, fixture.tracker.currentVersion());
            CompletableFuture.runAsync(completion::applicationCompleted).join();
            assertFalse(reload.toCompletableFuture().isDone());
            assertEquals(0L, fixture.tracker.currentVersion());
            fixture.scheduled.remove().run();
            assertFalse(reload.toCompletableFuture().isDone());
            fixture.scheduled.remove().run();
            reload.toCompletableFuture().join();
            assertTrue(fixture.tracker.dirtySince(fixture.worldId, 3, -5, 3, -5, 0L));
            assertFalse(fixture.tracker.dirtySince(fixture.worldId, 50, -5, 50, -5, 0L));
            assertThrows(IllegalStateException.class, completion::applicationStarted);
            assertEquals(1L, fixture.tracker.currentVersion());
            verify(listener).columnChanged(fixture.worldId, 3, -5);
            fixture.integration.cancelReload();
            EditSessionEvent resumed = event(queue(new BatchProcessorHolder()), EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(resumed);
            assertFalse(resumed.isCancelled());
            resumed.completeSession();
        }
    }

    @Test
    void reloadTimeoutReopensAdmissionAndKeepsTheExistingTracker() throws Exception {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            BatchProcessorHolder chain = new BatchProcessorHolder();
            EditSessionEvent active = event(queue(chain), EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(active);
            CompletionStage<Void> reload = fixture.integration.prepareReload(Duration.ofMillis(10L));
            assertThrows(ExecutionException.class, () -> reload.toCompletableFuture().get(2L, TimeUnit.SECONDS));
            EditSessionEvent resumed = event(queue(new BatchProcessorHolder()), EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(resumed);
            assertFalse(resumed.isCancelled());
            IChunk chunk = mock(IChunk.class);
            when(chunk.getX()).thenReturn(2);
            chain.postProcess(chunk, null, mock(IChunkSet.class));
            fixture.scheduled.remove().run();
            assertTrue(fixture.tracker.dirtySince(fixture.worldId, 2, 0, 2, 0, 0L));
            active.completeSession();
            resumed.completeSession();
            CompletableFuture<Void> prepared = fixture.integration.prepareReload(Duration.ofSeconds(1L)).toCompletableFuture();
            fixture.scheduled.remove().run();
            prepared.join();
        }
    }

    @Test
    void unloadAndReloadOfSameWorldUuidRetiresOldSessionGeneration() {
        try (Fixture fixture = new Fixture(List.of("existing.integration"))) {
            BatchProcessorHolder oldChain = new BatchProcessorHolder();
            EditSessionEvent old = event(queue(oldChain), EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(old);
            IChunk chunk = mock(IChunk.class);
            IChunkSet set = mock(IChunkSet.class);
            oldChain.postProcess(chunk, null, set);
            fixture.integration.onWorldUnload(new WorldUnloadEvent(fixture.world));
            fixture.integration.onWorldLoad(new WorldLoadEvent(fixture.world));
            oldChain.postProcess(chunk, null, set);
            fixture.scheduled.remove().run();
            assertEquals(0L, fixture.tracker.currentVersion());
            BatchProcessorHolder currentChain = new BatchProcessorHolder();
            EditSessionEvent current = event(queue(currentChain), EditSession.Stage.BEFORE_CHANGE);
            fixture.bus.post(current);
            currentChain.postProcess(chunk, null, set);
            fixture.scheduled.remove().run();
            assertTrue(fixture.tracker.dirtySince(fixture.worldId, 0, 0, 0, 0, 0L));
            old.completeSession();
            current.completeSession();
        }
    }

    private static Extent queue(BatchProcessorHolder chain) {
        Extent extent = mock(Extent.class);
        when(extent.addPostProcessor(any())).thenAnswer(invocation -> {
            IBatchProcessor processor = invocation.getArgument(0);
            chain.joinPost(processor);
            return extent;
        });
        return extent;
    }

    private static EditSessionEvent event(Extent extent, EditSession.Stage stage) {
        BukkitWorld world = mock(BukkitWorld.class);
        when(world.getNameUnsafe()).thenReturn("destination");
        when(world.appliedChangeSupportVersion()).thenReturn(1);
        EditSessionEvent event = new EditSessionEvent(world, null, -1, stage);
        event.setExtent(extent);
        return event;
    }

    private static final class Fixture implements AutoCloseable {
        private final List<String> previous = Settings.settings().EXTENT.ALLOWED_PLUGINS;
        private final WorldChangeTracker tracker = new WorldChangeTracker();
        private final Queue<Runnable> scheduled = new ArrayDeque<>();
        private final EventBus bus = new EventBus();
        private final UUID worldId = UUID.randomUUID();
        private final World world = mock(World.class);
        private final Logger logger = mock(Logger.class);
        private final FaweProjectionIntegration integration;

        private Fixture(List<String> allowed) {
            this(allowed, FaweProjectionIntegration.Mode.PATCHED);
        }

        private Fixture(List<String> allowed, FaweProjectionIntegration.Mode mode) {
            integration = new FaweProjectionIntegration(new FaweProjectionIntegration.Options(tracker, scheduled::add, mode));
            Settings.settings().EXTENT.ALLOWED_PLUGINS = new ArrayList<>(allowed);
            Plugin plugin = mock(Plugin.class);
            Server server = mock(Server.class);
            PluginManager pluginManager = mock(PluginManager.class);
            when(world.getName()).thenReturn("destination");
            when(world.getUID()).thenReturn(worldId);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getLogger()).thenReturn(logger);
            when(server.getWorlds()).thenReturn(List.of(world));
            when(server.getPluginManager()).thenReturn(pluginManager);
            integration.start(plugin, bus);
        }

        @Override
        public void close() {
            integration.close();
            Settings.settings().EXTENT.ALLOWED_PLUGINS = previous;
        }
    }

    private static final class MissingNativeScopeLoader extends ClassLoader {
        private MissingNativeScopeLoader() {
            super(FaweProjectionIntegration.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("com.fastasyncworldedit.core.util.task.NativeChangeScope")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
