package art.arcane.wormholes.render.clientview;

import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketAccess;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.render.client.session.ClientPreparedTravelServer;
import art.arcane.optics.plate.ChunkLease;
import org.mockito.MockedStatic;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.aperture.ApertureDescriptor;
import org.bukkit.plugin.Plugin;
import art.arcane.optics.plate.ChunkLeasePlatform;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.ClientViewMessage;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

class BukkitPreparedTravelLifecycleTest {
    @Test
    void closingDuringRetainReleasesTheNewLeaseAndCannotRetainAfterClosure() throws Exception {
        CountingChunks chunks = new CountingChunks();
        ChunkLeaseRegistry<World> registry = new ChunkLeaseRegistry<>(chunks, new ChunkLeaseRegistry.Options(0L, 1L, 1));
        BukkitChunkLeaseProvider.install(registry);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            World world = mock(World.class);
            when(world.getUID()).thenReturn(UUID.randomUUID());
            AutoCloseable preparation = preparation(world);
            Method retain = preparation.getClass().getDeclaredMethod("retain", ClientViewMessage.TravelCoordinate.class);
            retain.setAccessible(true);
            CountDownLatch closing = new CountDownLatch(1);
            CountDownLatch closed = new CountDownLatch(1);
            Future<?> retaining = workers.submit(() -> invokeRetain(retain, preparation));
            assertTrue(chunks.addStarted.await(5L, TimeUnit.SECONDS));
            Future<?> stopping = workers.submit(() -> close(preparation, closing, closed));
            assertTrue(closing.await(5L, TimeUnit.SECONDS));
            assertFalse(closed.await(100L, TimeUnit.MILLISECONDS));
            chunks.allowAdd.countDown();
            retaining.get(5L, TimeUnit.SECONDS);
            stopping.get(5L, TimeUnit.SECONDS);
            assertEquals(1, chunks.added.get());
            assertEquals(1, chunks.removed.get());
            invokeRetain(retain, preparation);
            preparation.close();
            assertEquals(1, chunks.added.get());
            assertEquals(1, chunks.removed.get());
        } finally {
            chunks.allowAdd.countDown();
            workers.shutdownNow();
            BukkitChunkLeaseProvider.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void oldAsyncCompletionCannotCloseASuccessorPreparationOrAnotherGeneration() throws Exception {
        BukkitPreparedTravel manager = new BukkitPreparedTravel(mock(Plugin.class), mock(ChunkPacketAccess.class));
        UUID player = UUID.randomUUID();
        AutoCloseable successor = preparation(mock(World.class));
        UUID token = UUID.randomUUID();
        ClientViewMessage.TravelBegin begin = mock(ClientViewMessage.TravelBegin.class);
        when(begin.token()).thenReturn(token);
        when(begin.generation()).thenReturn(7L);
        Field beginField = successor.getClass().getDeclaredField("begin");
        beginField.setAccessible(true);
        beginField.set(successor, begin);
        Field preparationsField = BukkitPreparedTravel.class.getDeclaredField("preparations");
        preparationsField.setAccessible(true);
        Map<UUID, Object> preparations = (Map<UUID, Object>) preparationsField.get(manager);
        preparations.put(player, successor);
        Field liveField = successor.getClass().getDeclaredField("live");
        liveField.setAccessible(true);
        AtomicBoolean live = (AtomicBoolean) liveField.get(successor);
        ClientViewMessage.TravelPose pose = new ClientViewMessage.TravelPose(0, 64, 0, 0, 0);
        manager.complete(player, new ClientViewMessage.TravelCommit(UUID.randomUUID(), 6L, 1L,
            "minecraft:overworld", "minecraft:the_nether", pose, new Vec3d(0, 0, 0)));
        manager.complete(player, new ClientViewMessage.TravelCommit(token, 6L, 1L,
            "minecraft:overworld", "minecraft:the_nether", pose, new Vec3d(0, 0, 0)));
        manager.complete(player, null);
        assertSame(successor, preparations.get(player));
        assertTrue(live.get());
        manager.complete(player, new ClientViewMessage.TravelCommit(token, 7L, 1L,
            "minecraft:overworld", "minecraft:the_nether", pose, new Vec3d(0, 0, 0)));
        assertTrue(preparations.isEmpty());
        assertFalse(live.get());
        manager.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void regionOwnedCaptureBatchHasFourSlotsNoDuplicatesAndNoWorkAfterClose() throws Exception {
        Plugin plugin = mock(Plugin.class);
        World world = mock(World.class);
        ChunkPacketAccess packets = mock(ChunkPacketAccess.class);
        BukkitPreparedTravel manager = new BukkitPreparedTravel(plugin, packets);
        AutoCloseable preparation = preparation(world);
        ClientPreparedTravelServer travel = mock(ClientPreparedTravelServer.class);
        AtomicInteger captureCursor = new AtomicInteger();
        when(travel.nextCapture()).thenAnswer(invocation ->
            new ClientViewMessage.TravelCoordinate(captureCursor.getAndIncrement(), 0));
        when(travel.nextRevision(any())).thenReturn(1);
        ChunkLeaseRegistry<World> registry = mock(ChunkLeaseRegistry.class);
        ChunkLease lease = mock(ChunkLease.class);
        when(registry.retain(eq(world), any(), anyInt(), anyInt())).thenReturn(lease);
        List<Runnable> callbacks = new ArrayList<>();
        Method schedule = BukkitPreparedTravel.class.getDeclaredMethod("scheduleColumns", ClientPreparedTravelServer.class,
            preparation.getClass(), long.class);
        schedule.setAccessible(true);
        Field capturingField = preparation.getClass().getDeclaredField("capturing");
        capturingField.setAccessible(true);
        Set<ClientViewMessage.TravelCoordinate> capturing = (Set<ClientViewMessage.TravelCoordinate>) capturingField.get(preparation);
        try (MockedStatic<BukkitChunkLeaseProvider> leases = mockStatic(BukkitChunkLeaseProvider.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            leases.when(BukkitChunkLeaseProvider::registry).thenReturn(registry);
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), eq(world), anyInt(), anyInt(), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    callbacks.add(invocation.getArgument(4));
                    return true;
                });
            schedule.invoke(manager, travel, preparation, Long.MAX_VALUE);
            assertEquals(4, callbacks.size());
            assertEquals(4, capturing.size());
            schedule.invoke(manager, travel, preparation, Long.MAX_VALUE);
            assertEquals(4, callbacks.size());
            preparation.close();
            for (Runnable callback : callbacks) {
                callback.run();
            }
            assertTrue(capturing.isEmpty());
            verifyNoInteractions(packets);
            schedule.invoke(manager, travel, preparation, Long.MAX_VALUE);
            assertEquals(4, callbacks.size());
        }
    }

    private static AutoCloseable preparation(World world) throws ReflectiveOperationException {
        Class<?> optionsType = Class.forName(BukkitPreparedTravel.class.getName() + "$PreparationOptions");
        Constructor<?> optionsConstructor = optionsType.getDeclaredConstructor(ApertureDescriptor.class, ProjectionEnvironment.Transform.class, UUID.class, UUID.class, World.class, Vec3d.class,
            ClientViewMessage.TravelPose.class, double.class, String.class, long.class);
        optionsConstructor.setAccessible(true);
        ApertureDescriptor geometry = new ApertureDescriptor(0, 64, 0, 0, true, 0, false, 1, 1, new long[]{1},
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, ApertureDescriptor.KIND_FRAME, 0.0D, 0, 1, List.of());
        Object options = optionsConstructor.newInstance(geometry, ProjectionEnvironment.Transform.IDENTITY, UUID.randomUUID(), UUID.randomUUID(), world, new Vec3d(0, 64, 0),
            new ClientViewMessage.TravelPose(0, 64, 0, 0, 0), 1.62D, "minecraft:overworld", 1L);
        Class<?> preparationType = Class.forName(BukkitPreparedTravel.class.getName() + "$Preparation");
        Constructor<?> constructor = preparationType.getDeclaredConstructor(optionsType);
        constructor.setAccessible(true);
        return (AutoCloseable) constructor.newInstance(options);
    }

    private static void invokeRetain(Method method, AutoCloseable preparation) {
        try {
            method.invoke(preparation, new ClientViewMessage.TravelCoordinate(0, 0));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void close(AutoCloseable preparation, CountDownLatch closing, CountDownLatch closed) {
        closing.countDown();
        try {
            preparation.close();
            closed.countDown();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class CountingChunks implements ChunkLeasePlatform<World> {
        private final CountDownLatch addStarted = new CountDownLatch(1);
        private final CountDownLatch allowAdd = new CountDownLatch(1);
        private final AtomicInteger added = new AtomicInteger();
        private final AtomicInteger removed = new AtomicInteger();

        @Override
        public CompletionStage<Boolean> add(World world, int chunkX, int chunkZ) {
            added.incrementAndGet();
            addStarted.countDown();
            try {
                if (!allowAdd.await(5L, TimeUnit.SECONDS)) {
                    return CompletableFuture.failedFuture(new AssertionError("Chunk retention did not resume"));
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> remove(World world, int chunkX, int chunkZ) {
            removed.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public boolean schedule(Runnable command, long delayMillis) {
            command.run();
            return true;
        }

        @Override
        public void reportFailure(Throwable failure) {
            throw new AssertionError(failure);
        }
    }
}
