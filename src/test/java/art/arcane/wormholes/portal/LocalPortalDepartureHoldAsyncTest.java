package art.arcane.wormholes.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.portal.LocalPortalTestSupport.FakeEntity;

final class LocalPortalDepartureHoldAsyncTest {
    private final World world = LocalPortalTestSupport.world("hold-async");
    private final LocalPortal portal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
    private final Location anchor = new Location(world, 0.5D, 65.0D, 1.0D);
    private final FakeEntity traveler = FakeEntity.player("hold-async", anchor);
    private final PinRuntime runtime = new PinRuntime();
    private final LocalPortalDepartureHold hold = new LocalPortalDepartureHold(portal, runtime);
    private final Traversive traversive = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchor.toVector());

    @AfterEach
    void clearTransit() {
        LocalPortal.clearTeleportInFlight(traveler.id());
        LocalPortal.clearReentryLatch(traveler.id());
        LocalPortal.clearTeleportCooldown(traveler.id());
    }

    @Test
    void pinUsesAsyncTeleportAndWaitsForOwnerCompletionBeforeContinuing() throws InterruptedException {
        beginPin();
        assertEquals(1, runtime.targets.size());
        assertEquals(anchor, runtime.targets.getFirst());
        assertEquals(1, traveler.teleports().size());
        assertTrue(runtime.tasks.isEmpty());
        assertTrue(hold.canCompleteDeparture(traveler.player(), traversive, traveler.player().getLocation()));
        traveler.player().teleport(anchor);
        Thread completion = Thread.ofPlatform().start(() -> runtime.teleport.complete(Boolean.TRUE));
        completion.join();

        assertEquals(1, runtime.tasks.size());
        assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        runtime.runNext();

        assertEquals(1, runtime.tasks.size());
        assertEquals(1, runtime.targets.size());
        assertTrue(hold.canCompleteDeparture(traveler.player(), traversive, anchor));
    }

    @Test
    void cancelledPinReleasesTheCurrentHandoffOnTheOwner() {
        beginPin();
        runtime.teleport.complete(Boolean.FALSE);
        assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));

        runtime.runNext();

        assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        assertFalse(hold.canCompleteDeparture(traveler.player(), traversive, anchor));
        assertTrue(runtime.tasks.isEmpty());
    }

    @Test
    void lateCancelledPinCannotCancelAReplacementHold() {
        beginPin();
        Traversive replacement = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchor.toVector());
        reacquireClaim();
        hold.startPlayerDepartureHold(traveler.player(), replacement, System.currentTimeMillis() + 12_000L, () -> { });

        runtime.teleport.complete(Boolean.FALSE);

        assertEquals(1, runtime.tasks.size());
        assertTrue(hold.canCompleteDeparture(traveler.player(), replacement, traveler.player().getLocation()));
        assertFalse(hold.canCompleteDeparture(traveler.player(), traversive, anchor));
        assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
    }

    @Test
    void queuedPinCompletionCannotAffectANewPlayerSession() {
        beginPin();
        runtime.teleport.complete(Boolean.FALSE);
        Player replacement = mock(Player.class);
        when(replacement.getUniqueId()).thenReturn(traveler.id());
        when(replacement.getLocation()).thenReturn(anchor);
        when(replacement.getWorld()).thenReturn(world);
        when(replacement.isValid()).thenReturn(true);
        Traversive next = LocalPortalTestSupport.traversive(portal, replacement, anchor.toVector());
        reacquireClaim();
        hold.startPlayerDepartureHold(replacement, next, System.currentTimeMillis() + 12_000L, () -> { });

        runtime.runNext();

        assertTrue(hold.canCompleteDeparture(replacement, next, anchor));
        assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        assertEquals(1, runtime.tasks.size());
    }

    @Test
    void retiredPinCompletionReleasesOnlyItsCurrentHold() {
        beginPin();
        runtime.teleport.complete(Boolean.TRUE);

        runtime.retired.removeFirst().run();

        assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        assertFalse(hold.canCompleteDeparture(traveler.player(), traversive, anchor));
    }

    @Test
    void rejectedCompletionSchedulerReleasesTheHandoff() {
        beginPin();
        runtime.acceptTasks = false;

        runtime.teleport.complete(Boolean.TRUE);

        assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        assertTrue(runtime.tasks.isEmpty());
    }

    @Test
    void completedPinDoesNotRestartAfterTheTransitClaimEnds() {
        beginPin();
        LocalPortal.clearTeleportInFlight(traveler.id());
        runtime.teleport.complete(Boolean.TRUE);

        runtime.runNext();

        assertTrue(runtime.tasks.isEmpty());
        assertEquals(1, runtime.targets.size());
    }

    @Test
    void rtpPinAlsoUsesAsyncTeleportAndWaitsForCompletion() {
        LocalPortal rtpPortal = LocalPortalTestSupport.portal(world, PortalType.RTP);
        Traversive rtpTraversive = LocalPortalTestSupport.traversive(rtpPortal, traveler.entity(), anchor.toVector());
        LocalPortalDepartureHold rtpHold = new LocalPortalDepartureHold(rtpPortal, runtime);
        assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        rtpHold.startRtpTraversalHold(traveler.entity(), rtpTraversive, () -> { });
        traveler.player().teleport(anchor.clone().add(1.0D, 0.0D, 0.0D));

        runtime.runNext();

        assertEquals(1, runtime.targets.size());
        assertEquals(1, traveler.teleports().size());
        assertTrue(runtime.tasks.isEmpty());
        traveler.player().teleport(anchor);
        runtime.teleport.complete(Boolean.TRUE);
        runtime.runNext();
        assertEquals(1, runtime.targets.size());
        assertEquals(1, runtime.tasks.size());
    }

    @Test
    void departureWaitsForPhysicalPinAndStopsFurtherSnapsAfterCommit() {
        beginPin();
        CompletableFuture<Boolean> preparation = hold.prepareDeparture(traveler.entity(), traversive).toCompletableFuture();
        assertFalse(preparation.isDone());
        assertFalse(hold.commitDeparture(traveler.entity(), traversive));
        traveler.player().teleport(anchor);
        runtime.teleport.complete(Boolean.TRUE);
        assertFalse(preparation.isDone());
        runtime.runNext();
        assertTrue(preparation.join());
        assertTrue(hold.commitDeparture(traveler.entity(), traversive));
        assertTrue(runtime.tasks.isEmpty());
        assertEquals(1, runtime.targets.size());
    }

    @Test
    void cancellationWaitsForPhysicalPinAndCompletesOnOwner() {
        beginPin();
        AtomicInteger cancellations = new AtomicInteger();
        hold.startPlayerDepartureHold(traveler.player(), traversive,
            System.currentTimeMillis() + 12_000L, cancellations::incrementAndGet);
        CompletableFuture<Boolean> cancelled = hold.cancelDepartureHold(traveler.player(), traversive).toCompletableFuture();
        assertFalse(cancelled.isDone());
        assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        runtime.teleport.complete(Boolean.TRUE);
        assertFalse(cancelled.isDone());
        runtime.runNext();
        assertTrue(cancelled.join());
        assertEquals(1, cancellations.get());
        assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
    }

    @Test
    void retiredCancellationNeverAuthorizesAnOwnerBounce() {
        beginPin();
        CompletableFuture<Boolean> cancelled = hold.cancelDepartureHold(traveler.player(), traversive).toCompletableFuture();
        runtime.teleport.complete(Boolean.TRUE);
        runtime.retired.removeFirst().run();
        assertFalse(cancelled.join());
        assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
    }

    @Test
    void replacementAtAnotherPortalWaitsForTheOldPhysicalPin() {
        beginPin();
        LocalPortal nextPortal = LocalPortalTestSupport.portal(world, PortalType.PORTAL);
        LocalPortalDepartureHold nextHold = new LocalPortalDepartureHold(nextPortal, runtime);
        Traversive next = LocalPortalTestSupport.traversive(nextPortal, traveler.entity(), anchor.toVector());
        reacquireClaim();
        nextHold.startPlayerDepartureHold(traveler.player(), next, System.currentTimeMillis() + 12_000L, () -> { });
        CompletableFuture<Boolean> prepared = nextHold.prepareDeparture(traveler.player(), next).toCompletableFuture();
        assertFalse(prepared.isDone());
        traveler.player().teleport(anchor);
        runtime.teleport.complete(Boolean.TRUE);
        while (!runtime.tasks.isEmpty()) {
            runtime.runNext();
        }
        assertTrue(prepared.join());
        assertTrue(nextHold.commitDeparture(traveler.entity(), next));
    }

    @Test
    void oldAdmissionCancellationCannotReleaseReplacementClaimOrAuthorizeBounce() {
        assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        Traversive replacement = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchor.toVector());
        hold.startPlayerDepartureHold(traveler.player(), traversive, System.currentTimeMillis() + 12_000L, () -> {
            reacquireClaim();
            hold.startPlayerDepartureHold(traveler.player(), replacement,
                System.currentTimeMillis() + 12_000L, () -> { });
        });
        CompletableFuture<Boolean> cancelled = hold.cancelDepartureHold(traveler.player(), traversive).toCompletableFuture();
        runtime.runNext();
        assertFalse(cancelled.join());
        assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        assertTrue(hold.canCompleteDeparture(traveler.player(), replacement, anchor));
    }

    @Test
    void preparationCannotOverwriteCancellationDuringOwnerValidation() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(traveler.id());
        when(player.isValid()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(anchor);
        Traversive crossing = LocalPortalTestSupport.traversive(portal, player, anchor.toVector());
        assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        hold.startPlayerDepartureHold(player, crossing, System.currentTimeMillis() + 12_000L, () -> { });
        AtomicBoolean cancelOnLocation = new AtomicBoolean(true);
        when(player.getLocation()).thenAnswer(invocation -> {
            if (cancelOnLocation.compareAndSet(true, false)) {
                hold.cancelDepartureHold(player, crossing);
            }
            return anchor;
        });
        CompletableFuture<Boolean> prepared = hold.prepareDeparture(player, crossing).toCompletableFuture();
        runtime.runNext();
        assertFalse(prepared.join());
        assertFalse(hold.commitDeparture(player, crossing));
        assertFalse(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
    }

    @Test
    void staleNoHoldRejectionCannotTouchAnotherCrossing() {
        assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        assertTrue(portal.bindDepartureClaim(traveler.entity(), traversive));
        reacquireClaim();
        Traversive replacement = LocalPortalTestSupport.traversive(portal, traveler.entity(), anchor.toVector());
        assertTrue(portal.bindDepartureClaim(traveler.entity(), replacement));
        hold.rejectDeparture(traveler.entity(), traversive);
        assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        assertTrue(traveler.teleports().isEmpty());
    }

    @Test
    void conditionalClaimReleaseWaitsForPhysicalDepartureAndPreservesReplacement() {
        assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        assertTrue(portal.bindDepartureClaim(traveler.entity(), traversive));
        CompletableFuture<Void> drain = portal.beginDepartureTeleport(traveler.entity());
        CompletableFuture<Boolean> released = portal.releaseDepartureClaim(traveler.entity(), traversive).toCompletableFuture();
        assertFalse(released.isDone());
        reacquireClaim();
        drain.complete(null);
        assertFalse(released.join());
        assertTrue(LocalPortal.isTeleportInFlight(traveler.id(), System.currentTimeMillis()));
    }

    private void reacquireClaim() {
        LocalPortal.clearTeleportInFlight(traveler.id());
        assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), System.currentTimeMillis()));
    }

    private void beginPin() {
        assertTrue(LocalPortal.markTeleportInFlight(traveler.id(), System.currentTimeMillis()));
        hold.startPlayerDepartureHold(traveler.player(), traversive, System.currentTimeMillis() + 12_000L, () -> { });
        traveler.player().teleport(anchor.clone().add(1.0D, 0.0D, 0.0D));
        runtime.runNext();
    }

    private static final class PinRuntime implements LocalPortalRuntime {
        private final List<Runnable> tasks = new ArrayList<Runnable>();
        private final List<Runnable> retired = new ArrayList<Runnable>();
        private final List<Location> targets = new ArrayList<Location>();
        private final CompletableFuture<Boolean> teleport = new CompletableFuture<Boolean>();
        private boolean acceptTasks = true;

        @Override
        public boolean dispatch(Entity entity, Runnable task, Runnable onRetired, long delayTicks) {
            if (!acceptTasks) {
                return false;
            }
            tasks.add(task);
            retired.add(onRetired);
            return true;
        }

        @Override
        public boolean dispatchRegion(World regionWorld, int chunkX, int chunkZ, Runnable task, long delayTicks) {
            throw new AssertionError("A hold must run on its entity owner");
        }

        @Override
        public CompletionStage<Boolean> teleport(Entity entity, Location target) {
            targets.add(target.clone());
            return teleport;
        }

        private void runNext() {
            retired.removeFirst();
            tasks.removeFirst().run();
        }
    }
}
