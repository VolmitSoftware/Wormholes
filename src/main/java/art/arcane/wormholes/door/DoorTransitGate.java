package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;

final class DoorTransitGate
{
	private static final double MOVEMENT_PROXIMITY = 2.25D;
	private static final double SWEPT_MARGIN = 1.0D;

	private DoorTransitGate()
	{
	}

	static Optional<DoorwayCrossing> detect(DoorwayPlane plane, DoorVec3 from, DoorVec3 to)
	{
		return detect(plane, from, to, 0.0D, 0.0D);
	}

	static Optional<DoorwayCrossing> detect(
		DoorwayPlane plane,
		DoorVec3 from,
		DoorVec3 to,
		double travelerHalfWidth,
		double travelerHeight)
	{
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		if(plane == null || !nearThreshold(plane, from, to))
		{
			return Optional.empty();
		}
		return plane.intersect(from, to, travelerHalfWidth, travelerHeight);
	}

	static boolean claim(DoorOpenCycle cycle, boolean openAtCrossing, boolean liveOpen)
	{
		DoorOpenCycle requiredCycle = Objects.requireNonNull(cycle, "cycle");
		if(!openAtCrossing)
		{
			requiredCycle.observe(liveOpen);
			return false;
		}
		return requiredCycle.tryBegin(liveOpen);
	}

	/**
	 * Object travelers never claim the single armed transit of an open cycle: a
	 * whole volley passes while the door is physically open, and none of them
	 * steals the transit a player is queued for.
	 */
	static boolean passThrough(DoorOpenCycle cycle, boolean openAtCrossing, boolean liveOpen)
	{
		Objects.requireNonNull(cycle, "cycle").observe(liveOpen);
		return openAtCrossing && liveOpen;
	}

	/**
	 * Ends a transit on the cycle it began on.
	 *
	 * <p>A transit that never claimed the cycle has nothing to complete, and it
	 * carries no fresh read of the door either: the caller only knows what it did
	 * to the door, and an object transit deliberately does nothing. Recording a
	 * state here would fabricate a shut door that is really still open, which stops
	 * the object sweep after the first arrow of a volley and hands a redstone-held
	 * door a fresh armed cycle it never swung for. Reconcile and the sweep are the
	 * only honest sources of that bit.</p>
	 */
	static void complete(DoorOpenCycle cycle, DoorTransit transit, boolean success, boolean open)
	{
		Objects.requireNonNull(cycle, "cycle");
		if(!Objects.requireNonNull(transit, "transit").claimsOpenCycle())
		{
			return;
		}
		try
		{
			cycle.complete(success, open);
		}
		catch(IllegalStateException ignored)
		{
			// A transit the cycle never admitted has nothing to complete.
		}
	}

	/**
	 * Speed-aware prefilter. A walking player never moves a full block per tick,
	 * but an arrow covers about three, so a fixed radius would discard the very
	 * segment that crosses the plane. The admitted band grows with the segment so
	 * the swept-segment crossing math stays the decision maker.
	 */
	private static boolean nearThreshold(DoorwayPlane plane, DoorVec3 from, DoorVec3 to)
	{
		DoorVec3 center = plane.center();
		double threshold = Math.max(MOVEMENT_PROXIMITY, segmentLength(from, to) + SWEPT_MARGIN);
		return Math.abs(from.x() - center.x()) <= threshold
			&& Math.abs(from.z() - center.z()) <= threshold
			&& Math.abs(to.x() - center.x()) <= threshold
			&& Math.abs(to.z() - center.z()) <= threshold;
	}

	private static double segmentLength(DoorVec3 from, DoorVec3 to)
	{
		double deltaX = to.x() - from.x();
		double deltaY = to.y() - from.y();
		double deltaZ = to.z() - from.z();
		return Math.sqrt((deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ));
	}
}
