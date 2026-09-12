package art.arcane.wormholes.hook;

import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.Traversive;
import org.bukkit.entity.Entity;

import java.util.Objects;

/**
 * One frame-portal traversal decision point. {@code tunnel} is null for RTP portals and for arrivals
 * without a resolvable inbound link; {@code traversive} is null during arrival gating.
 *
 * <p>{@code screening} marks an attempt raised for a rig member rather than the traveler who is
 * crossing. A gate answering a screening attempt must judge only who the member is: the root carries
 * the crossing, so nothing may be charged, stamped, or warmed up for a member.</p>
 */
public record TraversalAttempt(TraversalPhase phase, LocalPortal portal, Entity traveler, ITunnel tunnel,
                               Traversive traversive, long nowMillis, boolean screening) {
    public TraversalAttempt {
        phase = Objects.requireNonNull(phase, "phase");
        portal = Objects.requireNonNull(portal, "portal");
        traveler = Objects.requireNonNull(traveler, "traveler");
    }

    /** An ordinary attempt for the traveler who is crossing. */
    public TraversalAttempt(TraversalPhase phase, LocalPortal portal, Entity traveler, ITunnel tunnel,
                            Traversive traversive, long nowMillis) {
        this(phase, portal, traveler, tunnel, traversive, nowMillis, false);
    }
}
