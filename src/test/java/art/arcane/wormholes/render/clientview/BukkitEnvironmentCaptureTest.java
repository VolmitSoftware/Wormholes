package art.arcane.wormholes.render.clientview;

import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.platform.BukkitRegionTaskProvider;
import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.util.Direction;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BukkitEnvironmentCaptureTest {
    @Test
    void virtualEyeWaitsForOneLeaseAndReleasesAfterOwnerThreadCapture() throws Exception {
        World world = mock(World.class);
        UUID worldId = UUID.randomUUID();
        when(world.getUID()).thenReturn(worldId);
        when(world.isChunkLoaded(-3, 8)).thenReturn(true);
        ChunkLeaseRegistry<World> registry = mock(ChunkLeaseRegistry.class);
        ChunkLease lease = mock(ChunkLease.class);
        CompletableFuture<Boolean> ready = new CompletableFuture<Boolean>();
        when(lease.ready()).thenReturn(ready);
        when(registry.retain(eq(world), eq(worldId), anyInt(), anyInt())).thenReturn(lease);
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S,
            new GeometryVector(-128, 0, 0));
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        when(environment.transform()).thenReturn(transform);
        BukkitEnvironmentCapture.Request request = new BukkitEnvironmentCapture.Request(UUID.randomUUID(), null, UUID.randomUUID(), world,
            new GeometryVector(-32.5D, 92, 128), transform, 1);
        try (MockedStatic<BukkitChunkLeaseProvider> provider = mockStatic(BukkitChunkLeaseProvider.class);
             MockedStatic<WormholesPlatform> ownership = mockStatic(WormholesPlatform.class);
             MockedStatic<BukkitPortalEnvironment> nativeCapture = mockStatic(BukkitPortalEnvironment.class)) {
            provider.when(BukkitChunkLeaseProvider::registry).thenReturn(registry);
            ownership.when(() -> WormholesPlatform.isOwnedByCurrentRegion(world, -3, 8)).thenReturn(true);
            nativeCapture.when(() -> BukkitPortalEnvironment.capture(world, request.eye(), transform)).thenReturn(environment);
            BukkitEnvironmentCapture capture = new BukkitEnvironmentCapture();
            assertNull(capture.capture(request));
            assertNull(capture.capture(request));
            verify(registry, times(1)).retain(world, worldId, -3, 8);
            verify(lease, never()).close();
            ready.complete(true);
            verify(lease, times(1)).close();
            assertFalse(capture.unavailable(request.observer(), null, request.portal()));
            assertSame(environment, capture.capture(request));
        }
    }

    @Test
    void failedLeaseMakesOnlyThatPortalUnavailableWithoutSampling() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        ChunkLeaseRegistry<World> registry = mock(ChunkLeaseRegistry.class);
        ChunkLease lease = mock(ChunkLease.class);
        when(lease.ready()).thenReturn(CompletableFuture.completedFuture(false));
        when(registry.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S,
            new GeometryVector(0, 0, 0));
        BukkitEnvironmentCapture.Request request = new BukkitEnvironmentCapture.Request(UUID.randomUUID(), null, UUID.randomUUID(), world,
            new GeometryVector(0, 0, 0), transform, 1);
        try (MockedStatic<BukkitChunkLeaseProvider> provider = mockStatic(BukkitChunkLeaseProvider.class);
             MockedStatic<BukkitPortalEnvironment> nativeCapture = mockStatic(BukkitPortalEnvironment.class)) {
            provider.when(BukkitChunkLeaseProvider::registry).thenReturn(registry);
            BukkitEnvironmentCapture capture = new BukkitEnvironmentCapture();
            assertNull(capture.capture(request));
            assertTrue(capture.unavailable(request.observer(), null, request.portal()));
            assertFalse(capture.unavailable(request.observer(), null, UUID.randomUUID()));
            nativeCapture.verifyNoInteractions();
            verify(lease).close();
        }
    }

    @Test
    void observerRemovalCancelsQueuedRegionCaptureAndReleasesLease() {
        Fixture fixture = fixture();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        try (MockedStatic<BukkitChunkLeaseProvider> provider = mockStatic(BukkitChunkLeaseProvider.class);
             MockedStatic<WormholesPlatform> ownership = mockStatic(WormholesPlatform.class);
             MockedStatic<BukkitRegionTaskProvider> tasks = mockStatic(BukkitRegionTaskProvider.class);
             MockedStatic<BukkitPortalEnvironment> sampler = mockStatic(BukkitPortalEnvironment.class)) {
            provider.when(BukkitChunkLeaseProvider::registry).thenReturn(fixture.registry());
            tasks.when(() -> BukkitRegionTaskProvider.run(eq(fixture.request().world()), eq(0), eq(0), any(), any(), eq(0L)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(3));
                    return true;
                });
            fixture.capture().capture(fixture.request());
            fixture.ready().complete(true);
            verify(fixture.lease(), never()).close();
            fixture.capture().removeObserver(fixture.request().observer());
            queued.get().run();
            verify(fixture.lease(), times(1)).close();
            sampler.verifyNoInteractions();
        }
    }

    @Test
    void shutdownCancelsLoadingAndRejectsLateCapture() {
        Fixture fixture = fixture();
        try (MockedStatic<BukkitChunkLeaseProvider> provider = mockStatic(BukkitChunkLeaseProvider.class);
             MockedStatic<BukkitPortalEnvironment> sampler = mockStatic(BukkitPortalEnvironment.class)) {
            provider.when(BukkitChunkLeaseProvider::registry).thenReturn(fixture.registry());
            fixture.capture().capture(fixture.request());
            fixture.capture().close();
            fixture.ready().complete(true);
            assertNull(fixture.capture().capture(fixture.request()));
            verify(fixture.registry(), times(1)).retain(any(), any(), anyInt(), anyInt());
            verify(fixture.lease(), times(1)).close();
            sampler.verifyNoInteractions();
        }
    }

    @Test
    void movingEyeToAnotherChunkDiscardsCachedEnvironmentAndWaitsForNewLease() {
        Fixture fixture = fixture();
        ChunkLease secondLease = mock(ChunkLease.class);
        CompletableFuture<Boolean> secondReady = new CompletableFuture<>();
        when(secondLease.ready()).thenReturn(secondReady);
        when(fixture.registry().retain(any(), any(), eq(2), eq(0))).thenReturn(secondLease);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        BukkitEnvironmentCapture.Request first = fixture.request();
        BukkitEnvironmentCapture.Request second = new BukkitEnvironmentCapture.Request(first.observer(), null, first.portal(), first.world(),
            new GeometryVector(32, 80, 0), first.transform(), 2L);
        try (MockedStatic<BukkitChunkLeaseProvider> provider = mockStatic(BukkitChunkLeaseProvider.class);
             MockedStatic<WormholesPlatform> ownership = mockStatic(WormholesPlatform.class);
             MockedStatic<BukkitPortalEnvironment> sampler = mockStatic(BukkitPortalEnvironment.class)) {
            provider.when(BukkitChunkLeaseProvider::registry).thenReturn(fixture.registry());
            ownership.when(() -> WormholesPlatform.isOwnedByCurrentRegion(first.world(), 0, 0)).thenReturn(true);
            sampler.when(() -> BukkitPortalEnvironment.capture(first.world(), first.eye(), first.transform())).thenReturn(environment);
            fixture.capture().capture(first);
            fixture.ready().complete(true);
            assertSame(environment, fixture.capture().capture(first));
            assertNull(fixture.capture().capture(second));
            assertFalse(fixture.capture().unavailable(first.observer(), null, first.portal()));
            verify(secondLease, never()).close();
            fixture.capture().close();
            verify(secondLease).close();
        }
    }

    @Test
    void retiredRegionTaskReleasesLeaseAndOnlyRefusesThatView() {
        Fixture fixture = fixture();
        AtomicReference<Runnable> retired = new AtomicReference<>();
        try (MockedStatic<BukkitChunkLeaseProvider> provider = mockStatic(BukkitChunkLeaseProvider.class);
             MockedStatic<WormholesPlatform> ownership = mockStatic(WormholesPlatform.class);
             MockedStatic<BukkitRegionTaskProvider> tasks = mockStatic(BukkitRegionTaskProvider.class);
             MockedStatic<BukkitPortalEnvironment> sampler = mockStatic(BukkitPortalEnvironment.class)) {
            provider.when(BukkitChunkLeaseProvider::registry).thenReturn(fixture.registry());
            tasks.when(() -> BukkitRegionTaskProvider.run(eq(fixture.request().world()), eq(0), eq(0), any(), any(), eq(0L)))
                .thenAnswer(invocation -> {
                    retired.set(invocation.getArgument(4));
                    return true;
                });
            fixture.capture().capture(fixture.request());
            fixture.ready().complete(true);
            retired.get().run();
            verify(fixture.lease(), times(1)).close();
            assertTrue(fixture.capture().unavailable(fixture.request().observer(), null, fixture.request().portal()));
            assertFalse(fixture.capture().unavailable(fixture.request().observer(), UUID.randomUUID(), fixture.request().portal()));
            sampler.verifyNoInteractions();
        }
    }

    @Test
    void deadlineReleasesLeaseWhenRegionDispatchNeverRuns() throws InterruptedException {
        Fixture fixture = fixture();
        AtomicReference<Runnable> queued = new AtomicReference<>();
        try (MockedStatic<BukkitChunkLeaseProvider> provider = mockStatic(BukkitChunkLeaseProvider.class);
             MockedStatic<WormholesPlatform> ownership = mockStatic(WormholesPlatform.class);
             MockedStatic<BukkitRegionTaskProvider> tasks = mockStatic(BukkitRegionTaskProvider.class);
             MockedStatic<BukkitPortalEnvironment> sampler = mockStatic(BukkitPortalEnvironment.class)) {
            provider.when(BukkitChunkLeaseProvider::registry).thenReturn(fixture.registry());
            tasks.when(() -> BukkitRegionTaskProvider.run(eq(fixture.request().world()), eq(0), eq(0), any(), any(), eq(0L)))
                .thenAnswer(invocation -> {
                    queued.set(invocation.getArgument(3));
                    return true;
                });
            fixture.capture().capture(fixture.request());
            fixture.ready().complete(true);
            long deadline = System.nanoTime() + 7_000_000_000L;
            while (!fixture.capture().unavailable(fixture.request().observer(), null, fixture.request().portal())
                && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(fixture.capture().unavailable(fixture.request().observer(), null, fixture.request().portal()));
            queued.get().run();
            verify(fixture.lease(), times(1)).close();
            sampler.verifyNoInteractions();
        }
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        ChunkLeaseRegistry<World> registry = mock(ChunkLeaseRegistry.class);
        ChunkLease lease = mock(ChunkLease.class);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        when(lease.ready()).thenReturn(ready);
        when(registry.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S,
            new GeometryVector(0, 0, 0));
        BukkitEnvironmentCapture.Request request = new BukkitEnvironmentCapture.Request(UUID.randomUUID(), null, UUID.randomUUID(), world,
            new GeometryVector(0, 80, 0), transform, 1L);
        return new Fixture(new BukkitEnvironmentCapture(), request, registry, lease, ready);
    }

    private record Fixture(BukkitEnvironmentCapture capture, BukkitEnvironmentCapture.Request request, ChunkLeaseRegistry<World> registry,
                           ChunkLease lease, CompletableFuture<Boolean> ready) {
    }
}
