package art.arcane.wormholes.door;

import java.util.Objects;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.wormholes.door.view.DoorApertureFrames;

/**
 * How one doorway plane hands a traveler to another.
 *
 * <p>Two hinged doors keep the original front-to-front rule: the traveler leaves
 * the far door on the same side of it that it entered the near one, turned
 * around. Any pairing that involves a trapdoor is a straight-through route
 * instead, so falling into a hole in the floor drops out under the far plane and
 * a shot fired upward keeps climbing.</p>
 *
	 * <p>A closed-state trapdoor is the one exception: nothing can be delivered
	 * inside its solid plate, so arrivals always land on its exposed upper face.</p>
 */
public final class DoorPlanePairing
{
	static final int[] DOOR_ARRIVAL_Y_OFFSETS = {0, -1, 1, -2, 2};
	// A traveler leaving a trapdoor keeps going the way it was travelling. Never offer the
	// far side as a fallback: dropping in through the top has to come out under the plate,
	// and being spat back out of the face it entered is the one outcome worse than failing.
	private static final int[] TRAPDOOR_DOWN_ARRIVAL_Y_OFFSETS = {0, -1, -2, -3, -4};
	private static final int[] TRAPDOOR_UP_ARRIVAL_Y_OFFSETS = {0, 1, 2, 3, 4};

	private DoorPlanePairing()
	{
	}

	public static boolean mirrored(DoorwayPlane source, DoorwayPlane destination)
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		return !source.horizontal() && !destination.horizontal();
	}

	public static int arrivalSideSign(DoorwayPlane source, DoorwayPlane destination, int entrySideSign)
	{
		if(destination.horizontal() && destination.contactSurface())
		{
			return 1;
		}
		return mirrored(source, destination) ? entrySideSign : -entrySideSign;
	}

	public static Vec3d mapAperturePoint(DoorwayPlane source, DoorwayPlane destination, PlaneCrossing crossing)
	{
		DoorwayPlane requiredSource = Objects.requireNonNull(source, "source");
		DoorwayPlane requiredDestination = Objects.requireNonNull(destination, "destination");
		Vec3d point = Objects.requireNonNull(crossing, "crossing").point();
		double lateral = requiredSource.lateralOffset(point) * (mirrored(requiredSource, requiredDestination) ? -1.0D : 1.0D);
		double up = (requiredSource.horizontal() ? -requiredSource.secondaryOffset(point) : requiredSource.secondaryOffset(point) - 1.0D)
			* DoorApertureFrames.height(requiredDestination) / DoorApertureFrames.height(requiredSource);
		Frame frame = requiredDestination.frame();
		Vec3d center = requiredDestination.center();
		return new Vec3d(center.x() + (lateral * frame.getRight().x()) + (up * frame.getUp().x()),
			center.y() + (lateral * frame.getRight().y()) + (up * frame.getUp().y()),
			center.z() + (lateral * frame.getRight().z()) + (up * frame.getUp().z()));
	}

	/**
	 * Maps momentum between two planes of any orientation.
	 *
	 * <p>The vector is decomposed in the source plane's own frame - normal, lateral, and
	 * the third in-plane axis - and rebuilt in the destination's, so speed is preserved
	 * exactly. Two hinged doors keep the established front-to-front rule, where the
	 * traveler leaves the far door the way it came into the near one, so both
	 * in-plane-normal components flip. Every pairing that involves a trapdoor is a
	 * straight-through route instead: down stays down, up stays up.</p>
	 */
	public static Vec3d mapVector(DoorwayPlane source, DoorwayPlane destination, Vec3d vector)
	{
		return mapOriented(source, destination, vector, mirrored(source, destination) ? -1 : 1);
	}

	public static Vec3d mapVectorToSide(DoorwayPlane destination, DoorTransit transit, Vec3d vector, int sideSign)
	{
		if(sideSign != -1 && sideSign != 1)
		{
			throw new IllegalArgumentException("Arrival side must be -1 or 1");
		}
		return mapOriented(transit.sourcePlane(), destination, vector, sideSign * transit.exitSideSign());
	}

	/** Search ladder for a living arrival, ordered so the natural continuation wins. */
	public static int[] arrivalYOffsets(DoorwayPlane destination, int sideSign)
	{
		Objects.requireNonNull(destination, "destination");
		if(!destination.horizontal())
		{
			return DOOR_ARRIVAL_Y_OFFSETS;
		}
		return sideSign > 0 ? TRAPDOOR_UP_ARRIVAL_Y_OFFSETS : TRAPDOOR_DOWN_ARRIVAL_Y_OFFSETS;
	}

	private static Vec3d mapOriented(DoorwayPlane source, DoorwayPlane destination, Vec3d vector, int sign)
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		if(vector == null)
		{
			return null;
		}
		Frame from = sign > 0 ? source.frame() : source.frame().flipNormal();
		return OpticTransform.of(AxisPermutation.between(from, destination.frame()), 0.0D, 0.0D, 0.0D).vector(vector);
	}
}
