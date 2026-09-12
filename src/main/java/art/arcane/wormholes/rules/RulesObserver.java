package art.arcane.wormholes.rules;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalObserver;
import art.arcane.wormholes.hook.TraversalPhase;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.UniversalTunnel;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.UUID;

/**
 * Settles what the gate staged. A departure takes the traveler's rule costs once and starts the portal's
 * cooldown; a rejection or a failed delivery gives them back; an arrival commits them and runs the matched
 * rule's effects. The gate itself charges nothing, so a crossing the traversal loop defers costs nothing.
 */
public final class RulesObserver implements TraversalObserver {
    private final RuleTraversalLedger ledger;

    RulesObserver(RuleTraversalLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public void onDeparted(TraversalAttempt attempt) {
        if (attempt.phase() != TraversalPhase.DEPART) {
            return;
        }
        LocalPortal portal = attempt.portal();
        Entity traveler = attempt.traveler();
        UUID travelerId = traveler.getUniqueId();
        stampCooldown(portal, travelerId, attempt.nowMillis());
        RuleTraversalLedger.Pending pending = ledger.peek(travelerId);
        if (pending == null || !pending.portalId().equals(portal.getId())) {
            return;
        }
        if (!pending.costs().isEmpty() && !pending.reserved() && !take(portal, traveler, pending, attempt.nowMillis())) {
            ledger.clear(travelerId);
            return;
        }
        pending.dispatch(attempt.nowMillis());
        if (settlesLocally(attempt.tunnel())) {
            return;
        }
        pending.commit(attempt.nowMillis());
        if (pending.effects().isEmpty()) {
            ledger.clear(travelerId);
        }
    }

    /**
     * Whether this crossing ends in a local arrival the observer will see. When it does the costs stay
     * uncommitted until the traveler is delivered, because every step below the departure can still fail.
     * A gateway handoff and an RTP dispatch settle at the departure: there is no local arrival to wait for.
     */
    private static boolean settlesLocally(ITunnel tunnel) {
        if (tunnel == null || tunnel instanceof UniversalTunnel) {
            return false;
        }
        IPortal destination = tunnel.getDestination();
        return destination instanceof LocalPortal;
    }

    /** Takes the staged costs at the commitment point. The gate already tested that they were payable. */
    private static boolean take(LocalPortal portal, Entity traveler, RuleTraversalLedger.Pending pending, long nowMillis) {
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        if (extension == null || !(traveler instanceof Player player)) {
            return false;
        }
        RuleCostReservation reservation = pending.reserve(player, extension.charges(), nowMillis);
        if (reservation.successful()) {
            return true;
        }
        Wormholes.w("rules: " + player.getName() + " could not pay " + RuleCostText.describe(reservation.failedCost())
            + " at portal " + portal.getId() + " after the gate admitted them");
        return false;
    }

    /** Only the portal the traveler is carrying costs for may take them back; another portal's refusal is not theirs. */
    @Override
    public void onRejected(TraversalAttempt attempt, TraversalVerdict.Deny verdict) {
        UUID travelerId = attempt.traveler().getUniqueId();
        RuleTraversalLedger.Pending pending = ledger.peek(travelerId);
        if (pending == null || !pending.portalId().equals(attempt.portal().getId())) {
            return;
        }
        ledger.clear(travelerId);
        if (!pending.committed()) {
            pending.refund();
        }
    }

    @Override
    public void onArrived(LocalPortal destination, Entity traveler, Location arrival) {
        RuleTraversalLedger.Pending pending = ledger.take(traveler.getUniqueId());
        if (pending == null || !pending.dispatched()) {
            return;
        }
        pending.commit(System.currentTimeMillis());
        if (pending.effects().isEmpty()) {
            return;
        }
        EffectRunner.run(destination, traveler, arrival, pending.effects());
    }

    /**
     * The crossing died below the departure. Give the rule costs back and release the cooldown the
     * departure stamped, so the traveler can try again instead of paying for a crossing that never ran.
     */
    @Override
    public void onDeliveryFailed(LocalPortal portal, Entity traveler) {
        UUID travelerId = traveler.getUniqueId();
        RuleTraversalLedger.Pending pending = ledger.peek(travelerId);
        if (pending == null || !pending.dispatched() || pending.committed()) {
            return;
        }
        ledger.clear(travelerId);
        pending.refund();
        PortalCooldowns.stamp(travelerId, pending.portalId(), pending.cooldownGroup(), 0L, System.currentTimeMillis());
    }

    private static void stampCooldown(LocalPortal portal, UUID travelerId, long nowMillis) {
        RulesPortalExtension extension = portal.extension(RulesPortalExtension.class);
        if (extension == null) {
            return;
        }
        TraversalProfile profile = extension.document().profile();
        if (profile.cooldownMillis() <= 0L) {
            return;
        }
        PortalCooldowns.stamp(travelerId, portal.getId(), profile.cooldownGroup(), profile.cooldownMillis(), nowMillis);
    }
}
