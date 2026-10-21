package art.arcane.wormholes.chunk.arrival;

import art.arcane.optics.plate.ChunkLease;
import art.arcane.optics.plate.ChunkLeaseRegistry;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketAccess;
import art.arcane.volmlib.nativelib.chunk.ChunkPacketSnapshot;
import art.arcane.volmlib.nativelib.chunk.ChunkPosition;
import art.arcane.volmlib.nativelib.chunk.ChunkWorldContext;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.chunk.BukkitChunkLeaseProvider;
import art.arcane.wormholes.chunk.presend.ChunkPreSendSettings;
import art.arcane.wormholes.chunk.presend.ChunkPreSendOptions;
import art.arcane.wormholes.network.client.ArrivalMessage;
import art.arcane.wormholes.render.clientview.BukkitClientView;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class BukkitArrivalPreparationTest {
    @Test
    void completeTerrainRequiresRenderReadinessBeforeCommitAndReleasesEveryLease() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            ArrivalMessage.Prepare prepare = fixture.preparation();
            assertEquals(25, fixture.sent.stream().filter(ArrivalMessage.Chunk.class::isInstance).count());
            BukkitArrivalPreparation.receive(fixture.player, new ArrivalMessage.Ready(prepare.token(), prepare.revision(), ArrivalMessage.Readiness.RENDER));
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            attempt.beforeTeleport();
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Commit);
            attempt.complete();
            for (ChunkLease lease : fixture.leases) {
                verify(lease).close();
            }
        }
    }

    @Test
    void dataReadinessCannotCommitAndDirtyTerrainCannotBeAdopted() throws Exception {
        for (boolean dirty : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.prepareAll();
                ArrivalMessage.Prepare prepare = fixture.preparation();
                BukkitArrivalPreparation.receive(fixture.player, new ArrivalMessage.Ready(prepare.token(), prepare.revision(),
                    dirty ? ArrivalMessage.Readiness.RENDER : ArrivalMessage.Readiness.DATA));
                if (dirty) {
                    Wormholes.projectionChangeTracker.markChanged(fixture.world.getUID(), fixture.target.getBlockX(), fixture.target.getBlockZ());
                }
                BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target).beforeTeleport();
                assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Cancel);
                assertFalse(fixture.sent.stream().anyMatch(ArrivalMessage.Commit.class::isInstance));
            }
        }
    }

    @Test
    void retirementOfOldPreparationDoesNotRemoveItsReplacement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            UUID first = fixture.preparation().token();
            Runnable oldRetired = fixture.retired.getFirst();
            fixture.target.add(64, 0, 0);
            fixture.prepareAll();
            ArrivalMessage.Prepare replacement = fixture.preparation();
            assertFalse(first.equals(replacement.token()));
            oldRetired.run();
            BukkitArrivalPreparation.receive(fixture.player, new ArrivalMessage.Ready(replacement.token(), replacement.revision(), ArrivalMessage.Readiness.RENDER));
            BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target).beforeTeleport();
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Commit commit && commit.token().equals(replacement.token()));
        }
    }

    @Test
    void oldTeleportCompletionAndCancellationPreserveAReplacement() throws Exception {
        for (boolean completed : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture()) {
                fixture.prepareAll();
                fixture.ready();
                BukkitArrivalPreparation.Attempt old = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
                BukkitArrivalPreparation.disconnected(fixture.player.getUniqueId());
                fixture.target.add(64, 0, 0);
                fixture.prepareAll();
                ArrivalMessage.Prepare replacement = fixture.preparation();
                if (completed) {
                    old.complete();
                } else {
                    old.cancel();
                }
                BukkitArrivalPreparation.receive(fixture.player, new ArrivalMessage.Ready(replacement.token(),
                    replacement.revision(), ArrivalMessage.Readiness.RENDER));
                BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target).beforeTeleport();
                assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Commit commit
                    && commit.token().equals(replacement.token()));
            }
        }
    }

    @Test
    void rejectedCancellationSchedulingStillReleasesTheAttempt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            fixture.ready();
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            fixture.scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(fixture.player)).thenReturn(false);
            fixture.scheduler.when(() -> FoliaScheduler.runEntity(eq(fixture.plugin), eq(fixture.player),
                any(Runnable.class), anyLong(), any(Runnable.class))).thenReturn(false);
            attempt.cancel();
            for (ChunkLease lease : fixture.leases) {
                verify(lease).close();
            }
        }
    }

    @Test
    void readyTerrainCommitsWithoutSchedulingOrRecapturing() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            fixture.ready();
            int retained = fixture.leases.size();
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            assertTrue(BukkitArrivalPreparation.claimed(fixture.player));
            assertEquals(retained, fixture.leases.size());
            assertTrue(fixture.players.isEmpty());
            attempt.beforeTeleport();
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Commit);
            attempt.complete();
            assertFalse(BukkitArrivalPreparation.claimed(fixture.player));
        }
    }

    @Test
    void nearbyApproachCannotReplaceTheDestinationClaimedForTeleport() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            fixture.ready();
            ArrivalMessage.Prepare prepare = fixture.preparation();
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            assertTrue(BukkitArrivalPreparation.claimed(fixture.player));
            BukkitArrivalPreparation.prepare(fixture.player, fixture.target.clone().add(64, 0, 0));
            fixture.runRegions();
            assertEquals(prepare.token(), fixture.preparation().token());
            attempt.beforeTeleport();
            attempt.complete();
            assertFalse(BukkitArrivalPreparation.claimed(fixture.player));
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Commit commit && commit.token().equals(prepare.token()));
        }
    }

    @Test
    void incompleteTerrainFallsBackImmediatelyWithoutNewCaptureOrClientWait() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BukkitArrivalPreparation.prepare(fixture.player, fixture.target);
            assertEquals(1, fixture.leases.size());
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            attempt.beforeTeleport();
            attempt.complete();
            assertFalse(BukkitArrivalPreparation.claimed(fixture.player));
            assertTrue(fixture.players.isEmpty());
            fixture.runRegions();
            assertEquals(1, fixture.leases.size());
            verify(fixture.leases.getFirst()).close();
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Cancel);
            assertFalse(fixture.sent.stream().anyMatch(ArrivalMessage.Prepare.class::isInstance));
            assertFalse(fixture.sent.stream().anyMatch(ArrivalMessage.Commit.class::isInstance));
        }
    }

    @Test
    void missingPreparationFallsBackWithoutStartingCapture() {
        try (Fixture fixture = new Fixture()) {
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            attempt.beforeTeleport();
            attempt.complete();
            assertTrue(fixture.leases.isEmpty());
            assertTrue(fixture.players.isEmpty());
            assertTrue(fixture.sent.isEmpty());
        }
    }

    @Test
    void dataOnlyPreparationAndLateReadinessCannotDelayOrCommitTeleport() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            ArrivalMessage.Prepare prepare = fixture.preparation();
            BukkitArrivalPreparation.receive(fixture.player, new ArrivalMessage.Ready(prepare.token(), 1, ArrivalMessage.Readiness.DATA));
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            BukkitArrivalPreparation.receive(fixture.player, new ArrivalMessage.Ready(prepare.token(), 1, ArrivalMessage.Readiness.RENDER));
            attempt.beforeTeleport();
            attempt.complete();
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Cancel);
            assertFalse(fixture.sent.stream().anyMatch(ArrivalMessage.Commit.class::isInstance));
            assertTrue(fixture.players.isEmpty());
        }
    }

    @Test
    void staleMismatchedOrUnavailablePreparationCannotBeClaimed() throws Exception {
        for (int reason = 0; reason < 5; reason++) {
            try (Fixture fixture = new Fixture()) {
                fixture.prepareAll();
                fixture.ready();
                if (reason == 0) {
                    when(fixture.player.isOnline()).thenReturn(false);
                } else if (reason == 1) {
                    when(fixture.views.arrivalReceiver(fixture.player)).thenReturn(false);
                } else if (reason == 2) {
                    fixture.target.add(64, 0, 0);
                } else if (reason == 3) {
                    Wormholes.projectionChangeTracker.markChanged(fixture.worldId, fixture.target.getBlockX(), fixture.target.getBlockZ());
                } else {
                    ChunkPreSendSettings.apply(false, 3, 32, 2000);
                }
                BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
                attempt.beforeTeleport();
                attempt.complete();
                assertFalse(BukkitArrivalPreparation.claimed(fixture.player));
                assertFalse(fixture.sent.stream().anyMatch(ArrivalMessage.Commit.class::isInstance));
                assertTrue(fixture.players.isEmpty());
                for (ChunkLease lease : fixture.leases) {
                    verify(lease).close();
                }
            }
        }
    }

    @Test
    void terrainChangedAfterClaimCannotBeCommitted() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            fixture.ready();
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            Wormholes.projectionChangeTracker.markChanged(fixture.worldId, fixture.target.getBlockX(), fixture.target.getBlockZ());
            attempt.beforeTeleport();
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Cancel);
            assertFalse(fixture.sent.stream().anyMatch(ArrivalMessage.Commit.class::isInstance));
        }
    }

    @Test
    void stagedTransitionWaitsForRenderAcknowledgement() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            AtomicInteger continued = new AtomicInteger();
            AtomicInteger retired = new AtomicInteger();
            BukkitArrivalPreparation.prepareForTeleport(fixture.player, fixture.target,
                attempt -> { attempt.beforeTeleport(); continued.incrementAndGet(); }, retired::incrementAndGet);
            assertEquals(0, continued.get());
            fixture.ready();
            fixture.players.removeFirst().run();
            assertEquals(1, continued.get());
            assertEquals(0, retired.get());
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Commit);
        }
    }

    @Test
    void stagedTransitionTimeoutFallsBackWithoutCommittingUnreadyTerrain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            AtomicInteger continued = new AtomicInteger();
            AtomicInteger retired = new AtomicInteger();
            BukkitArrivalPreparation.prepareForTeleport(fixture.player, fixture.target,
                attempt -> { attempt.beforeTeleport(); continued.incrementAndGet(); }, retired::incrementAndGet);
            for (int tick = 0; tick < 10; tick++) {
                fixture.players.removeFirst().run();
            }
            assertEquals(1, continued.get());
            assertEquals(0, retired.get());
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Cancel);
            assertFalse(fixture.sent.stream().anyMatch(ArrivalMessage.Commit.class::isInstance));
            assertTrue(fixture.players.isEmpty());
        }
    }

    @Test
    void stagedTransitionRetiresAfterDisconnectWorldChangeOrReplacement() throws Exception {
        for (int reason = 0; reason < 3; reason++) {
            try (Fixture fixture = new Fixture()) {
                fixture.prepareAll();
                AtomicInteger continued = new AtomicInteger();
                AtomicInteger retired = new AtomicInteger();
                BukkitArrivalPreparation.prepareForTeleport(fixture.player, fixture.target,
                    attempt -> continued.incrementAndGet(), retired::incrementAndGet);
                if (reason == 0) {
                    when(fixture.player.isOnline()).thenReturn(false);
                } else if (reason == 1) {
                    when(fixture.player.getWorld()).thenReturn(mock(World.class));
                } else {
                    fixture.target.add(64, 0, 0);
                    BukkitArrivalPreparation.prepareForTeleport(fixture.player, fixture.target, ignored -> { }, () -> { });
                }
                fixture.players.removeFirst().run();
                assertEquals(0, continued.get());
                assertEquals(1, retired.get());
            }
        }
    }

    @Test
    void shutdownDuringStageConstructionCannotPublishOrRetainPreparation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.world.getUID()).thenAnswer(call -> {
                BukkitArrivalPreparation.shutdown();
                return fixture.worldId;
            });
            BukkitArrivalPreparation.prepare(fixture.player, fixture.target);
            assertTrue(fixture.retired.isEmpty());
            assertTrue(fixture.leases.isEmpty());
            assertTrue(fixture.sent.isEmpty());
        }
    }

    @Test
    void disablingChunkPreparationCancelsAnExistingStageAndSkipsNewStages() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.prepareAll();
            fixture.ready();
            BukkitArrivalPreparation.Attempt attempt = BukkitArrivalPreparation.claimForTeleport(fixture.player, fixture.target);
            ChunkPreSendSettings.apply(false, 3, 32, 2000);
            attempt.beforeTeleport();
            assertTrue(fixture.sent.getLast() instanceof ArrivalMessage.Cancel);
            int retained = fixture.leases.size();
            BukkitArrivalPreparation.prepare(fixture.player, fixture.target);
            assertEquals(retained, fixture.leases.size());
        }
    }

    @Test
    void unsupportedSnapshotCancelsWithoutPublishingPartialTerrain() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.packets.snapshot(eq(fixture.world), any())).thenReturn(Optional.empty());
            BukkitArrivalPreparation.prepare(fixture.player, fixture.target);
            fixture.runRegions();
            assertFalse(fixture.sent.stream().anyMatch(ArrivalMessage.Prepare.class::isInstance));
            verify(fixture.leases.getFirst()).close();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Plugin plugin = mock(Plugin.class);
        private final BukkitClientView views = mock(BukkitClientView.class);
        private final ChunkPacketAccess packets = mock(ChunkPacketAccess.class);
        private final World world = mock(World.class);
        private final UUID worldId = UUID.randomUUID();
        private final Player player = mock(Player.class);
        private final Location target = new Location(world, 1024.5, 80, 1024.5);
        private final List<ArrivalMessage> sent = new ArrayList<>();
        private final List<ChunkLease> leases = new ArrayList<>();
        private final List<Runnable> retired = new ArrayList<>();
        private final ArrayDeque<Runnable> regions = new ArrayDeque<>();
        private final ArrayDeque<Runnable> players = new ArrayDeque<>();
        private final MockedStatic<NativeAdapters> natives = mockStatic(NativeAdapters.class);
        private final MockedStatic<BukkitChunkLeaseProvider> provider = mockStatic(BukkitChunkLeaseProvider.class);
        private final MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class);
        private final WorldChangeTracker previousTracker = Wormholes.projectionChangeTracker;
        private final ChunkPreSendOptions previousOptions = ChunkPreSendSettings.active();

        private Fixture() {
            Server server = mock(Server.class);
            PluginManager manager = mock(PluginManager.class);
            when(plugin.getServer()).thenReturn(server);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("arrival-test"));
            when(server.getPluginManager()).thenReturn(manager);
            when(manager.getPlugins()).thenReturn(new Plugin[]{plugin});
            when(world.getUID()).thenReturn(worldId);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(player.getWorld()).thenReturn(world);
            when(views.arrivalReceiver(player)).thenReturn(true);
            when(views.sendArrival(eq(player), any())).thenAnswer(call -> {
                sent.add(call.getArgument(1));
                return true;
            });
            when(packets.snapshotSupported()).thenReturn(true);
            when(packets.context(world)).thenReturn(Optional.of(new ChunkWorldContext("minecraft:overworld", "minecraft:overworld",
                0L, false, false, 63, -64, 384, 0L, 0L, 0F, 0F)));
            when(packets.registryFingerprint(world)).thenReturn(Optional.of("a".repeat(64)));
            when(packets.snapshot(eq(world), any())).thenAnswer(call -> {
                ChunkPosition position = call.getArgument(1);
                return Optional.of(new ChunkPacketSnapshot(position, new byte[]{1, 2, 3}));
            });
            natives.when(() -> NativeAdapters.find(ChunkPacketAccess.class)).thenReturn(Optional.of(packets));
            ChunkLeaseRegistry<World> registry = mock(ChunkLeaseRegistry.class);
            provider.when(BukkitChunkLeaseProvider::registry).thenReturn(registry);
            when(registry.retain(eq(world), eq(worldId), anyInt(), anyInt())).thenAnswer(call -> {
                ChunkLease lease = mock(ChunkLease.class);
                when(lease.ready()).thenReturn(CompletableFuture.completedFuture(true));
                leases.add(lease);
                return lease;
            });
            scheduler.when(() -> FoliaScheduler.isOwnedByCurrentRegion(player)).thenReturn(true);
            scheduler.when(() -> FoliaScheduler.runRegion(eq(plugin), eq(world), anyInt(), anyInt(), any(Runnable.class), anyLong()))
                .thenAnswer(call -> {
                    regions.add(call.getArgument(4));
                    return true;
                });
            scheduler.when(() -> FoliaScheduler.runEntity(eq(plugin), eq(player), any(Runnable.class), anyLong(), any(Runnable.class)))
                .thenAnswer(call -> {
                    retired.add(call.getArgument(4));
                    if ((long) call.getArgument(3) == 1L) {
                        players.add(call.getArgument(2));
                    }
                    return true;
                });
            Wormholes.projectionChangeTracker = new WorldChangeTracker();
            ChunkPreSendSettings.apply(true, 3, 32, 2000);
            BukkitArrivalPreparation.install(plugin, views);
        }

        private void prepareAll() throws InterruptedException {
            BukkitArrivalPreparation.prepare(player, target);
            runRegions();
        }

        private void runRegions() throws InterruptedException {
            int attempts = 0;
            while (!regions.isEmpty() && attempts++ < 100) {
                int messages = sent.size();
                regions.removeFirst().run();
                if (!regions.isEmpty() && sent.size() == messages) {
                    Thread.sleep(51);
                }
            }
            assertTrue(regions.isEmpty(), "capture scheduler did not finish");
        }

        private void ready() {
            ArrivalMessage.Prepare prepare = preparation();
            BukkitArrivalPreparation.receive(player, new ArrivalMessage.Ready(prepare.token(), prepare.revision(), ArrivalMessage.Readiness.RENDER));
        }

        private ArrivalMessage.Prepare preparation() {
            return (ArrivalMessage.Prepare) sent.stream().filter(ArrivalMessage.Prepare.class::isInstance).toList().getLast();
        }

        @Override
        public void close() {
            BukkitArrivalPreparation.shutdown();
            ChunkPreSendSettings.apply(previousOptions.enabled(), previousOptions.radiusChunks(), previousOptions.maxChunks(),
                (int) (previousOptions.budgetNanos() / 1000L));
            Wormholes.projectionChangeTracker = previousTracker;
            scheduler.close();
            provider.close();
            natives.close();
        }
    }
}
