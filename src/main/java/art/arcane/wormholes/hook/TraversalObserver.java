package art.arcane.wormholes.hook;

import art.arcane.wormholes.portal.LocalPortal;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Passive traversal notifications. Called on the traveler's owning thread; never throw. */
public interface TraversalObserver {
    default void onDeparted(TraversalAttempt attempt) {
    }

    default void onArrived(LocalPortal destination, Entity traveler, Location arrival) {
    }

    default void onRejected(TraversalAttempt attempt, TraversalVerdict.Deny verdict) {
    }

    /**
     * The crossing failed after {@link #onDeparted}: the destination was undeliverable, refused the
     * traveler, or the teleport itself did not complete. Anything the departure took is owed back.
     */
    default void onDeliveryFailed(LocalPortal portal, Entity traveler) {
    }
}
