package art.arcane.wormholes.portal;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.LocalPortalTransitRegistry.ReentryLatch;
import art.arcane.wormholes.portal.LocalPortalTransitRegistry.TeleportClaim;
import art.arcane.wormholes.portal.rtp.RtpTraversalHoldPolicy;
import art.arcane.wormholes.service.WormholesHud;
import art.arcane.wormholes.service.WormholesTelemetry;

final class LocalPortalDepartureHold {
    static final long HOLD_NOTICE_DELAY_MILLIS = 500L;
    static final long HOLD_NOTICE_PERIOD_MILLIS = 1_000L;
    private static final Logger LOG = Logger.getLogger("Wormholes");

    private final LocalPortal portal;
    private final LocalPortalRuntime runtime;
    private final Map<UUID, Hold> holds = new ConcurrentHashMap<UUID, Hold>();

    LocalPortalDepartureHold(LocalPortal portal) {
        this(portal, LocalPortalRuntime.BUKKIT);
    }

    LocalPortalDepartureHold(LocalPortal portal, LocalPortalRuntime runtime) {
        this.portal = portal;
        this.runtime = runtime;
    }

    void startRtpTraversalHold(Entity entity, Traversive traversive, Runnable onCancel) {
        start(new Admission(entity, traversive, System.currentTimeMillis() + RtpTraversalHoldPolicy.TIMEOUT_MILLIS,
            onCancel), true);
    }

    void startPlayerDepartureHold(Player player, Traversive traversive, long deadlineMillis, Runnable onCancel) {
        start(new Admission(player, traversive, deadlineMillis, onCancel), false);
    }

    CompletionStage<Boolean> prepareDeparture(Entity entity, Traversive traversive) {
        Hold hold = matching(entity, traversive);
        if (hold == null || hold.phase.get() == Phase.FINISHED || hold.phase.get() == Phase.CANCELLING) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        hold.phase.compareAndSet(Phase.HOLDING, Phase.PREPARING);
        if (hold.pinDrain.isDone()) {
            completePreparation(hold);
        }
        return hold.departure;
    }

    CompletionStage<Boolean> cancelDepartureHold(Entity entity, Traversive traversive) {
        Hold hold = matching(entity, traversive);
        if (hold == null) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        if (!beginCancellation(hold)) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }
        if (hold.pinDrain.isDone()) {
            schedule(hold, 0L, false);
        }
        return hold.cancellation;
    }

    void rejectDeparture(Entity entity, Traversive traversive) {
        Hold hold = holds.get(entity.getUniqueId());
        if (hold == null) {
            TeleportClaim claim = LocalPortalTransitRegistry.departureClaim(entity, traversive);
            if (LocalPortalTransitRegistry.clearTeleportInFlight(entity.getUniqueId(), claim)) {
                portal.traversal().rejectDeparture(entity, traversive);
            }
            return;
        }
        if (hold.entity != entity || hold.traversive != traversive) {
            return;
        }
        cancelDepartureHold(entity, traversive).thenAccept(cancelled -> {
            if (Boolean.TRUE.equals(cancelled) && entity.isValid()) {
                portal.traversal().rejectDeparture(entity, traversive);
            }
        });
    }

    boolean commitDeparture(Entity entity, Traversive traversive) {
        Hold hold = matching(entity, traversive);
        if (hold != null && hold.phase.compareAndSet(Phase.PREPARED, Phase.FINISHED)
            && holds.remove(entity.getUniqueId(), hold)) {
            hold.cancellation.complete(Boolean.FALSE);
            return true;
        }
        return false;
    }

    boolean canCompleteDeparture(Player player, Traversive traversive, Location location) {
        Hold hold = matching(player, traversive);
        return hold != null && hold.phase.get() != Phase.CANCELLING && hold.phase.get() != Phase.FINISHED
            && validDeparture(hold, location, System.currentTimeMillis());
    }

    private void start(Admission admission, boolean rtp) {
        Entity entity = admission.entity();
        UUID entityId = entity.getUniqueId();
        Hold previous = holds.get(entityId);
        if (previous != null && previous.entity == entity && previous.traversive == admission.traversive()
            && previous.rtp == rtp && previous.phase.get() == Phase.HOLDING) {
            previous.onCancel = admission.onCancel();
            previous.deadlineMillis = Math.min(previous.deadlineMillis, admission.deadlineMillis());
            return;
        }
        LocalPortalTransitRegistry.bindDepartureClaim(entity, admission.traversive());
        Hold hold = new Hold(admission, rtp);
        hold.pinDrain = LocalPortalTransitRegistry.pendingTeleport(entity);
        holds.put(entityId, hold);
        if (previous != null) {
            previous.phase.set(Phase.FINISHED);
            cancelAdmission(previous);
            previous.pinDrain.whenComplete((ignored, error) -> {
                previous.departure.complete(Boolean.FALSE);
                previous.cancellation.complete(Boolean.FALSE);
            });
        }
        PortalStructure structure = portal.getStructure();
        if (structure == null || structure.getWorld() == null) {
            fail(hold, "the source world is unavailable");
            return;
        }
        if (hold.pinDrain.isDone()) {
            schedule(hold, 1L, true);
        } else {
            hold.pinDrain.whenComplete((ignored, error) -> schedule(hold, 1L, false));
        }
    }

    private Hold matching(Entity entity, Traversive traversive) {
        Hold hold = holds.get(entity.getUniqueId());
        return hold != null && hold.entity == entity && hold.traversive == traversive ? hold : null;
    }

    private boolean current(Hold hold) {
        return holds.get(hold.entity.getUniqueId()) == hold;
    }

    private void schedule(Hold hold, long delayTicks, boolean onOwner) {
        if (!current(hold) || hold.phase.get() == Phase.FINISHED || hold.phase.get() == Phase.PREPARED) {
            return;
        }
        if (!runtime.dispatch(hold.entity, () -> step(hold), () -> finishCancellation(hold, false), delayTicks)) {
            reportFailure(hold, "the entity scheduler rejected the hold");
            hold.bounce = hold.rtp;
            finishCancellation(hold, onOwner);
        }
    }

    private void step(Hold hold) {
        if (!current(hold) || !hold.pinDrain.isDone()) {
            return;
        }
        if (hold.phase.get() == Phase.CANCELLING) {
            finishCancellation(hold, true);
            return;
        }
        if (hold.phase.get() == Phase.PREPARING) {
            completePreparation(hold);
            return;
        }
        if (hold.phase.get() != Phase.HOLDING) {
            return;
        }
        Entity entity = hold.entity;
        if (!entity.isValid()) {
            finishCancellation(hold, false);
            return;
        }
        long now = System.currentTimeMillis();
        Location current = entity.getLocation();
        boolean sameWorld = hold.anchor.getWorld().equals(current.getWorld());
        double drift = sameWorld ? current.distanceSquared(hold.anchor) : Double.MAX_VALUE;
        double side = sameWorld ? LocalPortalTraversal.sourceSideDistance(hold.traversive, current.toVector()) : 0.0D;
        boolean inFlight = hold.claim != null
            && LocalPortalTransitRegistry.teleportClaim(entity.getUniqueId()) == hold.claim;
        if (hold.rtp) {
            ReentryLatch latch = LocalPortalTransitRegistry.activeReentryLatch(entity.getUniqueId(), now);
            boolean arrived = latch != null && portal.getId().equals(latch.portalId());
            RtpTraversalHoldPolicy.Decision decision = RtpTraversalHoldPolicy.decide(arrived, inFlight,
                sameWorld, side, drift, now - hold.startedMillis);
            switch (decision) {
                case STOP_ARRIVED -> finishWithoutCancellation(hold);
                case CANCEL_RETREAT -> finishCancellation(hold, true);
                case BOUNCE_FAILED, BOUNCE_TIMEOUT -> fail(hold, "the random teleport did not complete");
                case HOLD_FREE -> schedule(hold, 1L, true);
                case HOLD_PIN -> pinOrContinue(hold, current, drift, now);
            }
            return;
        }
        DepartureHoldPolicy.Decision decision = DepartureHoldPolicy.decide(inFlight, sameWorld, side,
            drift, hold.deadlineMillis - now);
        if (decision != DepartureHoldPolicy.Decision.HOLD_PIN) {
            finishCancellation(hold, true);
            return;
        }
        pinOrContinue(hold, current, drift, now);
    }

    private void pinOrContinue(Hold hold, Location current, double drift, long now) {
        if (now - hold.startedMillis >= HOLD_NOTICE_DELAY_MILLIS
            && now - hold.lastNoticeMillis >= HOLD_NOTICE_PERIOD_MILLIS && hold.entity instanceof Player player) {
            hold.lastNoticeMillis = now;
            WormholesHud.hold(player, Wormholes.text().component(hold.rtp
                ? WormholesMessages.PORTAL_RTP_NOT_READY : WormholesMessages.PORTAL_TRANSFER_HOLDING));
        }
        hold.entity.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
        if (drift <= DepartureHoldPolicy.LEASH_DRIFT_SQUARED) {
            schedule(hold, 1L, true);
            return;
        }
        Location target = hold.anchor.clone();
        target.setYaw(current.getYaw());
        target.setPitch(current.getPitch());
        CompletableFuture<Void> drain;
        synchronized (hold) {
            if (!current(hold) || hold.phase.get() != Phase.HOLDING) {
                return;
            }
            drain = LocalPortalTransitRegistry.beginTeleport(hold.entity);
            hold.pinDrain = drain;
        }
        CompletionStage<Boolean> teleport;
        try {
            teleport = Objects.requireNonNull(runtime.teleport(hold.entity, target), "hold teleport");
        } catch (RuntimeException error) {
            logPinFailure(hold, error);
            drain.complete(null);
            fail(hold, "the hold teleport failed");
            return;
        }
        teleport.whenComplete((success, error) -> {
            if (error != null) {
                logPinFailure(hold, error);
            }
            drain.complete(null);
            if (!current(hold)) {
                return;
            }
            Runnable completion = () -> {
                if (!current(hold)) {
                    return;
                }
                if (error != null || !Boolean.TRUE.equals(success)) {
                    fail(hold, "the hold teleport did not complete");
                } else {
                    step(hold);
                }
            };
            if (!runtime.dispatch(hold.entity, completion, () -> finishCancellation(hold, false), 0L)) {
                reportFailure(hold, "the entity scheduler rejected the hold teleport completion");
                finishCancellation(hold, false);
            }
        });
    }

    private void completePreparation(Hold hold) {
        if (!current(hold) || hold.phase.get() != Phase.PREPARING || !hold.pinDrain.isDone()) {
            return;
        }
        if (!hold.entity.isValid() || !validDeparture(hold, hold.entity.getLocation(), System.currentTimeMillis())) {
            finishCancellation(hold, true);
            return;
        }
        if (hold.phase.compareAndSet(Phase.PREPARING, Phase.PREPARED)) {
            hold.departure.complete(Boolean.TRUE);
        }
    }

    private boolean validDeparture(Hold hold, Location location, long now) {
        boolean sameWorld = hold.anchor.getWorld().equals(location.getWorld());
        double drift = sameWorld ? location.distanceSquared(hold.anchor) : Double.MAX_VALUE;
        double side = sameWorld ? LocalPortalTraversal.sourceSideDistance(hold.traversive, location.toVector()) : 0.0D;
        boolean inFlight = hold.claim != null
            && LocalPortalTransitRegistry.teleportClaim(hold.entity.getUniqueId()) == hold.claim;
        return DepartureHoldPolicy.decide(inFlight, sameWorld, side, drift, hold.deadlineMillis - now)
            == DepartureHoldPolicy.Decision.HOLD_PIN;
    }

    private void fail(Hold hold, String reason) {
        if (!current(hold)) {
            return;
        }
        if (!beginCancellation(hold)) {
            return;
        }
        hold.bounce = hold.rtp;
        reportFailure(hold, reason);
        if (hold.pinDrain.isDone()) {
            finishCancellation(hold, true);
        }
    }

    private void reportFailure(Hold hold, String reason) {
        if (!current(hold) || hold.failureReported) {
            return;
        }
        hold.failureReported = true;
        Wormholes.w((hold.rtp ? "Random teleport" : "Cross-server departure") + " hold at portal "
            + portal.getId() + " ended early for " + hold.entity.getUniqueId() + ": " + reason);
        WormholesTelemetry.countFailure(hold.rtp ? "TRAVERSAL_RTP_HOLD_FAILED" : "TRAVERSAL_DEPARTURE_HOLD_FAILED");
    }

    private boolean beginCancellation(Hold hold) {
        Phase phase;
        do {
            phase = hold.phase.get();
            if (phase == Phase.FINISHED) {
                return false;
            }
        } while (!hold.phase.compareAndSet(phase, Phase.CANCELLING));
        return true;
    }

    private void finishCancellation(Hold hold, boolean onOwner) {
        synchronized (hold) {
            if (!hold.pinDrain.isDone()) {
                beginCancellation(hold);
                return;
            }
            if (!holds.remove(hold.entity.getUniqueId(), hold)) {
                hold.cancellation.complete(Boolean.FALSE);
                hold.departure.complete(Boolean.FALSE);
                return;
            }
            hold.phase.set(Phase.FINISHED);
        }
        TeleportClaim currentClaim = LocalPortalTransitRegistry.teleportClaim(hold.entity.getUniqueId());
        boolean releasedCurrent = currentClaim == null || currentClaim == hold.claim;
        cancelAdmission(hold);
        LocalPortalTransitRegistry.clearTeleportInFlight(hold.entity.getUniqueId(), hold.claim);
        releasedCurrent = releasedCurrent && holds.get(hold.entity.getUniqueId()) == null
            && LocalPortalTransitRegistry.teleportClaim(hold.entity.getUniqueId()) == null;
        if (onOwner && releasedCurrent && hold.bounce && hold.entity.isValid()) {
            portal.traversal().bounceFailedRtpTraversal(hold.entity, hold.traversive);
        }
        hold.departure.complete(Boolean.FALSE);
        hold.cancellation.complete(Boolean.valueOf(onOwner && releasedCurrent));
    }

    private void cancelAdmission(Hold hold) {
        try {
            hold.onCancel.run();
        } catch (RuntimeException error) {
            LOG.log(Level.WARNING, "Failed to cancel portal hold admission for " + hold.entity.getUniqueId(), error);
        }
    }

    private void finishWithoutCancellation(Hold hold) {
        if (holds.remove(hold.entity.getUniqueId(), hold)) {
            hold.phase.set(Phase.FINISHED);
            hold.departure.complete(Boolean.FALSE);
            hold.cancellation.complete(Boolean.FALSE);
        }
    }

    private void logPinFailure(Hold hold, Throwable error) {
        LOG.log(Level.WARNING, "Portal hold teleport failed for " + hold.entity.getUniqueId() + " at " + portal.getId(), error);
    }

    private enum Phase {
        HOLDING, PREPARING, PREPARED, CANCELLING, FINISHED
    }

    private record Admission(Entity entity, Traversive traversive, long deadlineMillis, Runnable onCancel) {
        private Admission {
            Objects.requireNonNull(onCancel);
        }
    }

    private static final class Hold {
        private final Entity entity;
        private final Traversive traversive;
        private final Location anchor;
        private final TeleportClaim claim;
        private final boolean rtp;
        private final long startedMillis = System.currentTimeMillis();
        private final CompletableFuture<Boolean> departure = new CompletableFuture<Boolean>();
        private final CompletableFuture<Boolean> cancellation = new CompletableFuture<Boolean>();
        private volatile Runnable onCancel;
        private volatile long deadlineMillis;
        private final AtomicReference<Phase> phase = new AtomicReference<Phase>(Phase.HOLDING);
        private volatile CompletableFuture<Void> pinDrain = CompletableFuture.completedFuture(null);
        private long lastNoticeMillis;
        private boolean bounce;
        private volatile boolean failureReported;

        private Hold(Admission admission, boolean rtp) {
            this.entity = admission.entity();
            this.traversive = admission.traversive();
            this.anchor = entity.getLocation().clone();
            this.claim = LocalPortalTransitRegistry.departureClaim(entity, traversive);
            this.rtp = rtp;
            this.onCancel = admission.onCancel();
            this.deadlineMillis = admission.deadlineMillis();
        }
    }
}
