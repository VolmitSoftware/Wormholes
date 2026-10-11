package art.arcane.wormholes.network;

import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.convoy.ConvoyArrivalPlacer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftTransferReadinessTest extends MinecraftTestBase {
    @Test
    public void completionAfterShutdownNeverResolvesStoppedOrReplacementServer() throws Exception {
        for (PreparationKind kind : PreparationKind.values()) {
            ReadinessFixture fixture = new ReadinessFixture(kind);
            try {
                fixture.prepare();
                fixture.transfers.close();
                clearInvocations(fixture.runtime);
                when(fixture.runtime.server()).thenThrow(new IllegalStateException("stopped"));
                clearInvocations(fixture.runtime);
                assertTrue(fixture.ready.complete(true));
                assertEquals(0, fixture.queued.size());
                assertEquals(0, fixture.applied.get());
                assertEquals(1, fixture.rejected.get());
                verify(fixture.runtime, never()).server();
                verify(fixture.lease).close();
            } finally {
                fixture.transfers.close();
            }
            ReadinessFixture restarted = new ReadinessFixture(kind);
            MinecraftServer replacement = mock(MinecraftServer.class);
            try {
                restarted.prepare();
                restarted.transfers.close();
                when(restarted.runtime.server()).thenReturn(replacement);
                clearInvocations(restarted.runtime);
                assertTrue(restarted.ready.complete(true));
                verify(replacement, never()).execute(any(Runnable.class));
                verify(restarted.runtime, never()).server();
                assertEquals(0, restarted.applied.get());
                assertEquals(1, restarted.rejected.get());
            } finally {
                restarted.transfers.close();
            }
        }
    }

    @Test
    public void queuedCompletionCannotApplyAfterShutdownAndRestart() throws Exception {
        for (PreparationKind kind : PreparationKind.values()) {
            ReadinessFixture fixture = new ReadinessFixture(kind);
            MinecraftServer replacement = mock(MinecraftServer.class);
            try {
                fixture.prepare();
                assertTrue(fixture.ready.complete(true));
                assertEquals(1, fixture.queued.size());
                fixture.transfers.close();
                when(fixture.runtime.server()).thenReturn(replacement);
                clearInvocations(fixture.runtime);
                fixture.queued.getFirst().run();
                assertEquals(0, fixture.applied.get());
                assertEquals(1, fixture.rejected.get());
                verify(replacement, never()).execute(any(Runnable.class));
                verify(fixture.runtime, never()).server();
                verify(fixture.lease).close();
            } finally {
                fixture.transfers.close();
            }
        }
    }

    @Test
    public void activeCompletionUsesOriginalServerEvenIfRuntimeServerChanges() throws Exception {
        for (PreparationKind kind : PreparationKind.values()) {
            ReadinessFixture fixture = new ReadinessFixture(kind);
            MinecraftServer replacement = mock(MinecraftServer.class);
            try {
                fixture.prepare();
                when(fixture.runtime.server()).thenReturn(replacement);
                clearInvocations(fixture.runtime);
                assertTrue(fixture.ready.complete(true));
                assertEquals(1, fixture.queued.size());
                assertEquals(0, fixture.applied.get());
                verify(replacement, never()).execute(any(Runnable.class));
                verify(fixture.runtime, never()).server();
                fixture.queued.getFirst().run();
                assertEquals(1, fixture.applied.get());
                assertEquals(0, fixture.rejected.get());
                verify(fixture.lease).close();
            } finally {
                fixture.transfers.close();
            }
        }
    }

    @Test
    public void failedReadinessRejectsOnceAndReleasesLease() throws Exception {
        for (PreparationKind kind : PreparationKind.values()) {
            ReadinessFixture fixture = new ReadinessFixture(kind);
            try {
                fixture.prepare();
                assertTrue(fixture.ready.completeExceptionally(new IllegalStateException("chunk load failed")));
                assertEquals(1, fixture.queued.size());
                fixture.queued.getFirst().run();
                assertEquals(0, fixture.applied.get());
                assertEquals(1, fixture.rejected.get());
                verify(fixture.lease).close();
                fixture.transfers.close();
                assertEquals(1, fixture.rejected.get());
            } finally {
                fixture.transfers.close();
            }
        }
    }

    @Test
    public void placementFailureReleasesLeaseWithoutEscapingServerTask() throws Exception {
        for (PreparationKind kind : PreparationKind.values()) {
            ReadinessFixture fixture = new ReadinessFixture(kind);
            try {
                fixture.prepare(() -> {
                    fixture.applied.incrementAndGet();
                    throw new IllegalStateException("placement failed");
                });
                assertTrue(fixture.ready.complete(true));
                fixture.queued.getFirst().run();
                assertEquals(1, fixture.applied.get());
                verify(fixture.lease).close();
            } finally {
                fixture.transfers.close();
            }
        }
    }

    @Test
    public void refusedDispatchRetainsPreparationForShutdownRejection() throws Exception {
        for (PreparationKind kind : PreparationKind.values()) {
            ReadinessFixture fixture = new ReadinessFixture(kind);
            try {
                fixture.prepare();
                doThrow(new RejectedExecutionException("server executor stopped")).when(fixture.server).execute(any(Runnable.class));
                assertTrue(fixture.ready.complete(true));
                assertEquals(0, fixture.applied.get());
                assertEquals(0, fixture.rejected.get());
                fixture.transfers.close();
                assertEquals(1, fixture.rejected.get());
                verify(fixture.lease).close();
            } finally {
                fixture.transfers.close();
            }
        }
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private enum PreparationKind {
        ENTITY, CONVOY
    }

    private static final class ReadinessFixture {
        private final PreparationKind kind;
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final MinecraftPortal portal = mock(MinecraftPortal.class);
        private final ChunkLease lease = mock(ChunkLease.class);
        private final CompletableFuture<Boolean> ready = new CompletableFuture<>();
        private final List<Runnable> queued = new ArrayList<>();
        private final AtomicInteger applied = new AtomicInteger();
        private final AtomicInteger rejected = new AtomicInteger();
        private final MinecraftEntityTransfers transfers;

        @SuppressWarnings("unchecked")
        private ReadinessFixture(PreparationKind kind) {
            this.kind = kind;
            MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
            WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
            ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
            when(runtime.server()).thenReturn(server);
            when(runtime.portals()).thenReturn(portals);
            when(runtime.configuration()).thenReturn(configuration);
            when(configuration.settings()).thenReturn(new WormholesSettings(new MainConfig(), new ProjectionConfig(), new RenderConfig(), new NetworkConfig()));
            when(runtime.leases()).thenReturn(leases);
            when(portal.getWorldKey()).thenReturn("minecraft:overworld");
            when(portals.resolveLevel(portal)).thenReturn(level);
            when(leases.retain(eq(level), any(UUID.class), anyInt(), anyInt())).thenReturn(lease);
            when(lease.ready()).thenReturn(ready);
            doAnswer(call -> {
                queued.add(call.getArgument(0, Runnable.class));
                return null;
            }).when(server).execute(any(Runnable.class));
            transfers = new MinecraftEntityTransfers(runtime, mock(NetworkManager.class));
        }

        private void prepare() throws ReflectiveOperationException {
            prepare(applied::incrementAndGet);
        }

        @SuppressWarnings("unchecked")
        private void prepare(Runnable task) throws ReflectiveOperationException {
            Vec3d point = new Vec3d(1, 64, 1);
            if (kind == PreparationKind.ENTITY) {
                InboundEntityTransfers.Host<Entity, MinecraftPortal, PlaneCrossing, Vec3d> host =
                    (InboundEntityTransfers.Host<Entity, MinecraftPortal, PlaneCrossing, Vec3d>) field(transfers, "arrivalHost");
                WireMessage.EntityTransfer transfer = new WireMessage.EntityTransfer(UUID.randomUUID(), UUID.randomUUID(), new byte[0], null);
                InboundEntityTransfers.Arrival<MinecraftPortal, PlaneCrossing, Vec3d> arrival = new InboundEntityTransfers.Arrival<>(
                    "source", transfer, portal, new InboundEntityTransfers.Target<>(null, point), null);
                assertTrue(host.schedule(arrival, new InboundEntityTransfers.Task(task, rejected::incrementAndGet)));
                return;
            }
            MinecraftConvoys convoys = (MinecraftConvoys) field(transfers, "convoys");
            Object arrivals = field(convoys, "arrivals");
            ConvoyArrivalPlacer.Spawner<Entity, MinecraftPortal, PlaneCrossing, Object> spawner =
                (ConvoyArrivalPlacer.Spawner<Entity, MinecraftPortal, PlaneCrossing, Object>) field(arrivals, "spawner");
            for (Class<?> type : MinecraftConvoys.class.getDeclaredClasses()) {
                if (type.getSimpleName().equals("Destination")) {
                    Constructor<?> constructor = type.getDeclaredConstructor(MinecraftPortal.class, Vec3d.class);
                    constructor.setAccessible(true);
                    assertTrue(spawner.runRegion(constructor.newInstance(portal, point), task, rejected::incrementAndGet));
                    return;
                }
            }
            throw new IllegalStateException("Convoy destination type is missing");
        }
    }
}
