package qa;

import art.arcane.volmlib.util.json.JSONObject;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import art.arcane.wormholes.portal.rtp.RtpDestination;
import art.arcane.wormholes.portal.rtp.RtpRuntimeSnapshot;
import art.arcane.wormholes.portal.rtp.RtpService;
import art.arcane.wormholes.service.WormholesTelemetry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

final class RtpRetirementFixture {
    private final JavaPlugin plugin;
    private final UUID portalId;
    private final Map<UUID, Trial> trials = new ConcurrentHashMap<>();
    private Trial retiring;
    private Trial followup;
    private long failuresBefore;
    private boolean retiredVerified;
    private boolean followupVerified;

    RtpRetirementFixture(JavaPlugin plugin, UUID portalId) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.portalId = Objects.requireNonNull(portalId, "portalId");
    }

    public void start(Player recipient, boolean retire) {
        if (retire && retiring != null || !retire && (followup != null || !retiredVerified)) {
            throw new IllegalStateException("Retirement trials must run once, with cleanup verified before the followup");
        }
        LocalPortal portal = portal();
        if (!Wormholes.rtpRuntime.isReady(portalId)) {
            throw new IllegalStateException("RTP destination is not ready");
        }
        if (retire) {
            failuresBefore = WormholesTelemetry.failures();
        }
        Location source = new Location(portal.getWorld(), -12.5D, 101.0D, -4.0D);
        Pig mob = portal.getWorld().spawn(source, Pig.class, this::configure);
        Trial trial = new Trial(mob, retire);
        trials.put(mob.getUniqueId(), trial);
        if (retire) {
            retiring = trial;
        } else {
            followup = trial;
        }
        scheduleAdmission(trial, recipient, 10);
    }

    public void report(Player recipient) {
        reply(recipient, "RETIREMENT STATE " + state());
    }

    public void verify(Player recipient) {
        if (retiring == null || !retiring.removed || retiring.retirementCallbacks.get() != 1) {
            throw new IllegalStateException("The first mob did not retire after teleport");
        }
        RtpRuntimeSnapshot runtime = runtime();
        if (runtime.sharedClaims() != 0 || runtime.anonymousClaims() != 0 || runtime.playerClaims() != 0) {
            throw new IllegalStateException("RTP traversal claims remain after arrival");
        }
        if (WormholesTelemetry.failures() != failuresBefore) {
            throw new IllegalStateException("RTP traversal increased the failure counter");
        }
        if (!retiredVerified) {
            if (LocalPortal.clearTeleportInFlight(retiring.entityId)) {
                throw new IllegalStateException("The removed mob retained teleport in-flight bookkeeping");
            }
            retiredVerified = true;
        }
        if (followup != null) {
            if (!followup.arrived || !followup.valid || followup.arrivalPendingNow) {
                throw new IllegalStateException("The followup mob has not completed a normal arrival");
            }
            if (LocalPortal.clearTeleportInFlight(followup.entityId)) {
                throw new IllegalStateException("The followup mob retained teleport in-flight bookkeeping");
            }
            followupVerified = true;
        }
        report(recipient);
    }

    public CompletableFuture<Void> cleanup() {
        List<CompletableFuture<Void>> removals = new ArrayList<>(trials.size());
        for (Trial trial : trials.values()) {
            removals.add(remove(trial));
        }
        trials.clear();
        retiring = null;
        followup = null;
        retiredVerified = false;
        followupVerified = false;
        return CompletableFuture.allOf(removals.toArray(new CompletableFuture<?>[0]));
    }

    private void configure(Pig mob) {
        mob.setAI(false);
        mob.setGravity(false);
        mob.setInvulnerable(true);
        mob.setSilent(true);
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(false);
    }

    private void scheduleAdmission(Trial trial, Player recipient, int attemptsRemaining) {
        boolean scheduled = trial.mob.getScheduler().execute(plugin,
            () -> admit(trial, recipient, attemptsRemaining), () -> rejectAdmission(trial, recipient), 1L);
        if (!scheduled) {
            rejectAdmission(trial, recipient);
        }
    }

    private void admit(Trial trial, Player recipient, int attemptsRemaining) {
        try {
            if (!trial.mob.isValid()) {
                if (attemptsRemaining > 1) {
                    scheduleAdmission(trial, recipient, attemptsRemaining - 1);
                    return;
                }
                throw new IllegalStateException("The fixture mob did not become valid within 10 owned ticks");
            }
            LocalPortal portal = portal();
            if (portal.canContinueRtpTraversal(trial.mob)) {
                throw new IllegalStateException("The portal captured the fixture mob before controlled admission");
            }
            RtpDestination destination = Objects.requireNonNull(runtime().active(), "active RTP destination");
            Location source = trial.mob.getLocation();
            trial.target = new Location(source.getWorld(), destination.blockX() + 0.5D,
                destination.feetY(), destination.blockZ() + 0.5D);
            Traversive traversal = new Traversive(trial.mob, portal.getFrame(), portal.getOrigin(), source.toVector(),
                new Vector(), new Vector(0.0D, 0.0D, 1.0D));
            scheduleArrivalObservation(trial, 200);
            if (!Wormholes.rtpRuntime.traverse(portal, trial.mob, traversal)) {
                throw new IllegalStateException("The real RTP runtime rejected controlled admission");
            }
            trial.arrivalPendingNow = true;
            trial.admission = "requested";
            reply(recipient, "RETIREMENT START retire=" + trial.retire + " entity=" + trial.entityId);
        } catch (RuntimeException failure) {
            fail(trial, failure);
            reply(recipient, "FIXTURE failed " + failure.getClass().getSimpleName());
        }
    }

    private void rejectAdmission(Trial trial, Player recipient) {
        trial.error = "The mob scheduler retired before RTP admission";
        reply(recipient, "FIXTURE failed RTP admission retired");
    }

    private void scheduleArrivalObservation(Trial trial, int attemptsRemaining) {
        boolean scheduled = trial.mob.getScheduler().execute(plugin,
            () -> observeArrival(trial, attemptsRemaining),
            () -> trial.error = "Mob retired before its destination could be observed", 1L);
        if (!scheduled) {
            throw new IllegalStateException("The mob scheduler rejected arrival observation");
        }
    }

    private void observeArrival(Trial trial, int attemptsRemaining) {
        try {
            if (!trial.error.isEmpty()) {
                return;
            }
            Location actual = trial.mob.getLocation();
            Location target = trial.target;
            trial.arrived = trial.mob.isValid() && actual.getWorld().equals(target.getWorld())
                && actual.distanceSquared(target) < 0.01D;
            if (!trial.arrived) {
                if (attemptsRemaining <= 1) {
                    throw new IllegalStateException("The mob did not reach the active RTP destination within 200 owned ticks");
                }
                scheduleArrivalObservation(trial, attemptsRemaining - 1);
                return;
            }
            trial.teleportObserved = true;
            trial.pendingArrival = portal().canContinueRtpTraversal(trial.mob);
            trial.arrivalPendingNow = trial.pendingArrival;
            ScheduledTask sentinel = trial.mob.getScheduler().runDelayed(plugin,
                ignored -> trial.sentinelExecuted = true, trial.retirementCallbacks::incrementAndGet, 2L);
            if (sentinel == null) {
                throw new IllegalStateException("The mob scheduler rejected its arrival sentinel");
            }
            if (trial.retire) {
                if (!trial.pendingArrival) {
                    throw new IllegalStateException("Arrival bookkeeping completed before mob removal");
                }
                trial.mob.remove();
                trial.valid = false;
                trial.arrivalPendingNow = false;
                trial.removed = true;
            } else {
                scheduleCompletionObservation(trial, 200);
            }
        } catch (RuntimeException failure) {
            fail(trial, failure);
        }
    }

    private void scheduleCompletionObservation(Trial trial, int attemptsRemaining) {
        boolean scheduled = trial.mob.getScheduler().execute(plugin,
            () -> observeCompletion(trial, attemptsRemaining), () -> unexpectedRetirement(trial), 1L);
        if (!scheduled) {
            unexpectedRetirement(trial);
        }
    }

    private void observeCompletion(Trial trial, int attemptsRemaining) {
        try {
            trial.valid = trial.mob.isValid();
            trial.arrivalPendingNow = trial.valid && portal().canContinueRtpTraversal(trial.mob);
            if (!trial.valid) {
                throw new IllegalStateException("The followup mob disappeared before completing arrival");
            }
            if (trial.arrivalPendingNow) {
                if (attemptsRemaining <= 1) {
                    throw new IllegalStateException("The followup mob retained arrival bookkeeping for 200 ticks");
                }
                scheduleCompletionObservation(trial, attemptsRemaining - 1);
            }
        } catch (RuntimeException failure) {
            fail(trial, failure);
        }
    }

    private void unexpectedRetirement(Trial trial) {
        trial.valid = false;
        trial.arrivalPendingNow = false;
        trial.error = "The followup mob retired before arrival observation completed";
    }

    private CompletableFuture<Void> remove(Trial trial) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        boolean scheduled = trial.mob.getScheduler().execute(plugin, () -> removeOnOwner(trial, completion),
            () -> completion.complete(null), 1L);
        if (!scheduled) {
            completion.complete(null);
        }
        return completion;
    }

    private void removeOnOwner(Trial trial, CompletableFuture<Void> completion) {
        try {
            if (trial.mob.isValid()) {
                trial.mob.remove();
            }
            trial.valid = false;
            completion.complete(null);
        } catch (RuntimeException failure) {
            completion.completeExceptionally(failure);
        }
    }

    private JSONObject state() {
        RtpRuntimeSnapshot runtime = runtime();
        JSONObject result = new JSONObject();
        result.put("failuresBefore", failuresBefore);
        result.put("failuresNow", WormholesTelemetry.failures());
        result.put("sharedClaims", runtime.sharedClaims());
        result.put("anonymousClaims", runtime.anonymousClaims());
        result.put("playerClaims", runtime.playerClaims());
        result.put("retiredVerified", retiredVerified);
        result.put("followupVerified", followupVerified);
        if (retiring != null) {
            result.put("retired", describe(retiring));
        }
        if (followup != null) {
            result.put("followup", describe(followup));
        }
        return result;
    }

    private JSONObject describe(Trial trial) {
        JSONObject result = new JSONObject();
        result.put("entity", trial.entityId.toString());
        result.put("admission", trial.admission);
        result.put("teleportObserved", trial.teleportObserved);
        result.put("arrived", trial.arrived);
        result.put("pendingArrivalAtObservation", trial.pendingArrival);
        result.put("removed", trial.removed);
        result.put("retirementCallbacks", trial.retirementCallbacks.get());
        result.put("sentinelExecuted", trial.sentinelExecuted);
        result.put("valid", trial.valid);
        result.put("arrivalPendingNow", trial.arrivalPendingNow);
        result.put("error", trial.error);
        if (trial.target != null) {
            result.put("x", trial.target.getX());
            result.put("y", trial.target.getY());
            result.put("z", trial.target.getZ());
        }
        return result;
    }

    private LocalPortal portal() {
        ILocalPortal portal = Wormholes.portalManager.getLocalPortal(portalId);
        if (!(portal instanceof LocalPortal local)) {
            throw new IllegalStateException("RTP fixture portal is missing");
        }
        return local;
    }

    private RtpRuntimeSnapshot runtime() {
        RtpService.Snapshot snapshot = Wormholes.rtpRuntime.snapshotOrNull(portalId);
        if (snapshot == null) {
            throw new IllegalStateException("RTP runtime snapshot is missing");
        }
        return snapshot.runtime();
    }

    private void reply(Player recipient, String message) {
        recipient.getScheduler().execute(plugin, () -> recipient.sendMessage(message), null, 1L);
    }

    private void fail(Trial trial, RuntimeException failure) {
        trial.error = failure.getMessage();
        plugin.getLogger().log(Level.SEVERE, "RTP retirement fixture failed for " + trial.entityId, failure);
    }

    private static final class Trial {
        private final Pig mob;
        private final UUID entityId;
        private final boolean retire;
        private final AtomicInteger retirementCallbacks = new AtomicInteger();
        private volatile Location target;
        private volatile boolean teleportObserved;
        private volatile boolean arrived;
        private volatile boolean pendingArrival;
        private volatile boolean removed;
        private volatile boolean sentinelExecuted;
        private volatile boolean valid;
        private volatile boolean arrivalPendingNow;
        private volatile String error = "";
        private volatile String admission = "pending";

        private Trial(Pig mob, boolean retire) {
            this.mob = mob;
            entityId = mob.getUniqueId();
            this.retire = retire;
            valid = mob.isValid();
        }
    }
}
