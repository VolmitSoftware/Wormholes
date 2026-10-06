package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;

public final class DoorTransitGate
{
	private static final double MOVEMENT_PROXIMITY = 2.25D;
	private static final double SWEPT_MARGIN = 1.0D;

	private DoorTransitGate()
	{
	}

	public static Optional<PlaneCrossing> detect(DoorwayPlane plane, Vec3d from, Vec3d to)
	{
		return detect(plane, from, to, 0.0D, 0.0D);
	}

	public static Optional<PlaneCrossing> detect(
		DoorwayPlane plane,
		Vec3d from,
		Vec3d to,
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

	public static Optional<PlaneCrossing> prepared(DoorwayPlane plane, PlaneCrossing crossing,
		double travelerHalfWidth, double travelerHeight)
	{
		Objects.requireNonNull(crossing, "crossing");
		if(plane == null)
		{
			return Optional.empty();
		}
		Frame frame = DoorApertureFrames.of(plane);
		if(!frame.view(crossing.frontSide()).equals(crossing.frame()) || Math.abs(plane.signedDistance(crossing.origin())) > 0.00001D)
		{
			return Optional.empty();
		}
		Face normal = frame.getNormal();
		double side = (crossing.frontSide() ? 1.0D : -1.0D)
			* (normal.x() * plane.normalX() + normal.y() * plane.normalY() + normal.z() * plane.normalZ());
		Vec3d feet = crossing.point();
		double distance = plane.signedDistance(feet);
		Vec3d surface = new Vec3d(feet.x() - plane.normalX() * distance,
			feet.y() - plane.normalY() * distance, feet.z() - plane.normalZ() * distance);
		double reach = Math.max(travelerHalfWidth, travelerHeight) + 0.01D;
		Vec3d from = new Vec3d(surface.x() + plane.normalX() * side * reach,
			surface.y() + plane.normalY() * side * reach, surface.z() + plane.normalZ() * side * reach);
		Vec3d to = new Vec3d(surface.x() - plane.normalX() * side * 0.01D,
			surface.y() - plane.normalY() * side * 0.01D, surface.z() - plane.normalZ() * side * 0.01D);
		return detect(plane, from, to, travelerHalfWidth, travelerHeight);
	}

	public static boolean claim(DoorOpenCycle cycle, boolean openAtCrossing, boolean liveOpen)
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
	public static boolean passThrough(DoorOpenCycle cycle, boolean openAtCrossing, boolean liveOpen)
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
	public static void complete(DoorOpenCycle cycle, DoorTransit transit, boolean success, boolean open)
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
	private static boolean nearThreshold(DoorwayPlane plane, Vec3d from, Vec3d to)
	{
		Vec3d center = plane.center();
		double threshold = Math.max(MOVEMENT_PROXIMITY, segmentLength(from, to) + SWEPT_MARGIN);
		return Math.abs(from.x() - center.x()) <= threshold
			&& Math.abs(from.z() - center.z()) <= threshold
			&& Math.abs(to.x() - center.x()) <= threshold
			&& Math.abs(to.z() - center.z()) <= threshold;
	}

	private static double segmentLength(Vec3d from, Vec3d to)
	{
		double deltaX = to.x() - from.x();
		double deltaY = to.y() - from.y();
		double deltaZ = to.z() - from.z();
		return Math.sqrt((deltaX * deltaX) + (deltaY * deltaY) + (deltaZ * deltaZ));
	}
}
