package art.arcane.wormholes.transit;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.hook.TraversalAttempt;
import art.arcane.wormholes.hook.TraversalObserver;
import art.arcane.wormholes.hook.TraversalVerdict;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;

/**
 * Turns a bounce denial into a reflected velocity (the entry velocity mirrored across the aperture
 * plane) and plays the threshold and arrival cues on departure and arrival.
 */
public final class TransitObserver implements TraversalObserver {
    @Override
    public void onDeparted(TraversalAttempt attempt) {
        ThresholdCue.play(attempt);
    }

    @Override
    public void onArrived(LocalPortal destination, Entity traveler, Location arrival) {
        ArrivalCue.play(destination, traveler, arrival);
    }

    @Override
    public void onRejected(TraversalAttempt attempt, TraversalVerdict.Deny verdict) {
        if (verdict.reason() != TransitMessages.BOUNCED) {
            return;
        }
        Traversive traversive = attempt.traversive();
        Entity traveler = attempt.traveler();
        if (traversive == null || traveler == null) {
            return;
        }
        traveler.setVelocity(reflect(traversive));
    }

    static Vector reflect(Traversive traversive) {
        Vector normal = traversive.getInFrame().getNormal().toVector();
        Vector velocity = traversive.getInVelocity().clone();
        double along = velocity.dot(normal);
        return velocity.subtract(normal.multiply(2.0D * along)).multiply(Settings.portalPushback(1.0D));
    }
}
