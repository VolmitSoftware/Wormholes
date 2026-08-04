package art.arcane.wormholes.door;

import java.util.Objects;

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
final class DoorPlanePairing
{
	// A traveler leaving a trapdoor keeps going the way it was travelling. Never offer the
	// far side as a fallback: dropping in through the top has to come out under the plate,
	// and being spat back out of the face it entered is the one outcome worse than failing.
	private static final int[] TRAPDOOR_DOWN_ARRIVAL_Y_OFFSETS = {0, -1, -2, -3, -4};
	private static final int[] TRAPDOOR_UP_ARRIVAL_Y_OFFSETS = {0, 1, 2, 3, 4};

	private DoorPlanePairing()
	{
	}

	static boolean mirrored(DoorwayPlane source, DoorwayPlane destination)
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		return !source.horizontal() && !destination.horizontal();
	}

	static int arrivalSideSign(
		DoorwayPlane source,
		DoorwayPlane destination,
		DoorwayCrossing.Direction direction)
	{
		Objects.requireNonNull(direction, "direction");
		if(destination.horizontal() && destination.contactSurface())
		{
			return 1;
		}
		return mirrored(source, destination) ? direction.entrySideSign() : direction.exitSideSign();
	}

	static float arrivalYaw(DoorwayPlane source, DoorwayPlane destination, float yaw)
	{
		return mirrored(source, destination)
			? source.rotateYawToMatchingSide(destination, yaw)
			: source.rotateYawTo(destination, yaw);
	}

	static DoorVec3 mapAperturePoint(
		DoorwayPlane source,
		DoorwayPlane destination,
		DoorwayCrossing crossing)
	{
		DoorwayPlane requiredSource = Objects.requireNonNull(source, "source");
		DoorwayPlane requiredDestination = Objects.requireNonNull(destination, "destination");
		DoorwayCrossing requiredCrossing = Objects.requireNonNull(crossing, "crossing");
		DoorVec3 destinationCenter = requiredDestination.center();
		double lateral = requiredCrossing.lateralOffset()
			* (mirrored(requiredSource, requiredDestination) ? -1.0D : 1.0D);
		double secondary = requiredSource.horizontal()
			? -2.0D * requiredCrossing.verticalOffset()
			: requiredCrossing.verticalOffset() - 1.0D;
		double x = destinationCenter.x()
			+ (lateral * -requiredDestination.facing().getModZ());
		double y = destinationCenter.y();
		double z = destinationCenter.z()
			+ (lateral * requiredDestination.facing().getModX());
		if(requiredDestination.horizontal())
		{
			x += secondary * -requiredDestination.facing().getModX() * 0.5D;
			z += secondary * -requiredDestination.facing().getModZ() * 0.5D;
		}
		else
		{
			y += secondary;
		}
		return new DoorVec3(x, y, z);
	}

	/** Search ladder for a living arrival, ordered so the natural continuation wins. */
	static int[] arrivalYOffsets(DoorwayPlane destination, int sideSign)
	{
		Objects.requireNonNull(destination, "destination");
		if(!destination.horizontal())
		{
			return DimensionalDoorManager.DOOR_ARRIVAL_Y_OFFSETS;
		}
		return sideSign > 0 ? TRAPDOOR_UP_ARRIVAL_Y_OFFSETS : TRAPDOOR_DOWN_ARRIVAL_Y_OFFSETS;
	}
}
