package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftEnvironmentCaptureTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void waitsForSavedEyeChunkOutsideMeshAndSamplesOnlyOnServerThread() {
        Fixture fixture = fixture();
        MinecraftEnvironmentCapture.Request request = request(fixture, new GeometryVector(-32.5D, 92, 128));
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        try (MockedStatic<MinecraftPortalEnvironment> sampler = mockStatic(MinecraftPortalEnvironment.class)) {
            sampler.when(() -> MinecraftPortalEnvironment.capture(fixture.world(), request.eye(), request.transform(), fixture.world().isFlat())).thenReturn(environment);
            assertNull(fixture.capture().capture(request));
            assertNull(fixture.capture().capture(request));
            assertFalse(fixture.capture().unavailable(request.observer(), null, request.portal()));
            verify(fixture.leases(), times(1)).retain(eq(fixture.world()), any(UUID.class), eq(-3), eq(8));
            fixture.ready().complete(true);
            sampler.verifyNoInteractions();
            verify(fixture.lease(), never()).close();
            fixture.ownerTasks().remove().run();
            sampler.verify(() -> MinecraftPortalEnvironment.capture(fixture.world(), request.eye(), request.transform(), fixture.world().isFlat()), times(1));
            verify(fixture.lease(), times(1)).close();
            assertSame(environment, fixture.capture().capture(request));
            fixture.capture().close();
        }
    }

    @Test
    public void unavailableLeaseRefusesOnlyItsViewWithoutSamplingGeneratorBiomes() {
        Fixture fixture = fixture();
        MinecraftEnvironmentCapture.Request request = request(fixture, new GeometryVector(0, 80, 0));
        try (MockedStatic<MinecraftPortalEnvironment> sampler = mockStatic(MinecraftPortalEnvironment.class)) {
            assertNull(fixture.capture().capture(request));
            fixture.ready().complete(false);
            assertTrue(fixture.capture().unavailable(request.observer(), null, request.portal()));
            assertFalse(fixture.capture().unavailable(request.observer(), UUID.randomUUID(), request.portal()));
            assertNull(fixture.capture().capture(request));
            verify(fixture.leases(), times(1)).retain(any(), any(), anyInt(), anyInt());
            verify(fixture.lease()).close();
            sampler.verifyNoInteractions();
        }
    }

    @Test
    public void missingChunkAfterReadinessReleasesLeaseWithoutGeneratorSampling() {
        Fixture fixture = fixture();
        MinecraftEnvironmentCapture.Request request = request(fixture, new GeometryVector(0, 80, 0));
        when(fixture.world().getChunkSource().getChunkNow(0, 0)).thenReturn(null);
        try (MockedStatic<MinecraftPortalEnvironment> sampler = mockStatic(MinecraftPortalEnvironment.class)) {
            fixture.capture().capture(request);
            fixture.ready().complete(true);
            fixture.ownerTasks().remove().run();
            assertTrue(fixture.capture().unavailable(request.observer(), null, request.portal()));
            verify(fixture.lease(), times(1)).close();
            sampler.verifyNoInteractions();
        }
    }

    @Test
    public void observerRemovalReleasesLeaseAndDiscardsQueuedCapture() {
        Fixture fixture = fixture();
        MinecraftEnvironmentCapture.Request request = request(fixture, new GeometryVector(0, 80, 0));
        try (MockedStatic<MinecraftPortalEnvironment> sampler = mockStatic(MinecraftPortalEnvironment.class)) {
            fixture.capture().capture(request);
            fixture.ready().complete(true);
            fixture.capture().removeObserver(request.observer());
            fixture.ownerTasks().remove().run();
            verify(fixture.lease(), times(1)).close();
            assertFalse(fixture.capture().unavailable(request.observer(), null, request.portal()));
            sampler.verifyNoInteractions();
        }
    }

    @Test
    public void shutdownReleasesPendingLeaseAndIgnoresLateReadiness() {
        Fixture fixture = fixture();
        MinecraftEnvironmentCapture.Request request = request(fixture, new GeometryVector(0, 80, 0));
        try (MockedStatic<MinecraftPortalEnvironment> sampler = mockStatic(MinecraftPortalEnvironment.class)) {
            fixture.capture().capture(request);
            fixture.capture().close();
            fixture.ready().complete(true);
            while (!fixture.ownerTasks().isEmpty()) {
                fixture.ownerTasks().remove().run();
            }
            verify(fixture.lease(), times(1)).close();
            sampler.verifyNoInteractions();
        }
    }

    @Test
    public void movingToAnotherChunkCancelsOldSampleAndWaitsForNewLease() {
        Fixture fixture = fixture();
        MinecraftEnvironmentCapture.Request first = request(fixture, new GeometryVector(0, 80, 0));
        MinecraftEnvironmentCapture.Request second = new MinecraftEnvironmentCapture.Request(first.observer(), null, first.portal(),
            first.world(), new GeometryVector(32, 80, 0), first.transform(), 2L);
        ChunkLease secondLease = mock(ChunkLease.class);
        CompletableFuture<Boolean> secondReady = new CompletableFuture<>();
        when(secondLease.ready()).thenReturn(secondReady);
        when(fixture.leases().retain(eq(fixture.world()), any(UUID.class), eq(2), eq(0))).thenReturn(secondLease);
        ClientViewEnvironment environment = mock(ClientViewEnvironment.class);
        try (MockedStatic<MinecraftPortalEnvironment> sampler = mockStatic(MinecraftPortalEnvironment.class)) {
            sampler.when(() -> MinecraftPortalEnvironment.capture(fixture.world(), second.eye(), second.transform(), fixture.world().isFlat())).thenReturn(environment);
            fixture.capture().capture(first);
            fixture.ready().complete(true);
            assertNull(fixture.capture().capture(second));
            verify(fixture.lease()).close();
            fixture.ownerTasks().remove().run();
            sampler.verifyNoInteractions();
            secondReady.complete(true);
            fixture.ownerTasks().remove().run();
            assertSame(environment, fixture.capture().capture(second));
            fixture.capture().close();
        }
    }

    @Test(timeout = 8000L)
    public void deadlineReleasesLeaseEvenWhenOwnerDispatchNeverRuns() throws InterruptedException {
        Fixture fixture = fixture();
        MinecraftEnvironmentCapture.Request request = request(fixture, new GeometryVector(0, 80, 0));
        try (MockedStatic<MinecraftPortalEnvironment> sampler = mockStatic(MinecraftPortalEnvironment.class)) {
            fixture.capture().capture(request);
            fixture.ready().complete(true);
            long deadline = System.nanoTime() + 7_000_000_000L;
            while (!fixture.capture().unavailable(request.observer(), null, request.portal()) && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(fixture.capture().unavailable(request.observer(), null, request.portal()));
            fixture.ownerTasks().remove().run();
            verify(fixture.lease(), times(1)).close();
            sampler.verifyNoInteractions();
        }
    }

    private static MinecraftEnvironmentCapture.Request request(Fixture fixture, GeometryVector eye) {
        return new MinecraftEnvironmentCapture.Request(UUID.randomUUID(), null, UUID.randomUUID(), fixture.world(), eye,
            new ClientViewEnvironment.Transform(Direction.E, Direction.U, Direction.S, new GeometryVector(-128, 0, 0)), 1L);
    }

    @SuppressWarnings("unchecked")
    private static Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel world = mock(ServerLevel.class);
        ServerChunkCache chunks = mock(ServerChunkCache.class);
        ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        ChunkLease lease = mock(ChunkLease.class);
        CompletableFuture<Boolean> ready = new CompletableFuture<>();
        Queue<Runnable> tasks = new ArrayDeque<>();
        when(runtime.server()).thenReturn(server);
        when(runtime.leases()).thenReturn(leases);
        when(world.dimension()).thenReturn(Level.OVERWORLD);
        when(world.getChunkSource()).thenReturn(chunks);
        when(chunks.getChunkNow(anyInt(), anyInt())).thenReturn(mock(LevelChunk.class));
        when(leases.retain(any(), any(), anyInt(), anyInt())).thenReturn(lease);
        when(lease.ready()).thenReturn(ready);
        doAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return null;
        }).when(server).execute(any(Runnable.class));
        return new Fixture(new MinecraftEnvironmentCapture(runtime), world, leases, lease, ready, tasks);
    }

    private record Fixture(MinecraftEnvironmentCapture capture, ServerLevel world, ChunkLeaseRegistry<ServerLevel> leases,
                           ChunkLease lease, CompletableFuture<Boolean> ready, Queue<Runnable> ownerTasks) {
    }
}
