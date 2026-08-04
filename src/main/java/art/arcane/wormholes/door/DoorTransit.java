package art.arcane.wormholes.door;

import java.util.Objects;

public record DoorTransit(
	DoorwayPlane sourcePlane,
	DoorwayCrossing crossing,
	float yaw,
	float pitch,
	double halfWidth,
	double height,
	DoorTravelerClass travelerClass,
	DoorVec3 velocity)
{
	public DoorTransit(DoorwayPlane sourcePlane, DoorwayCrossing.Direction direction, float yaw, float pitch)
	{
		this(sourcePlane, direction, yaw, pitch, 0.3D, 1.8D);
	}

	public DoorTransit(
		DoorwayPlane sourcePlane,
		DoorwayCrossing.Direction direction,
		float yaw,
		float pitch,
		double halfWidth,
		double height)
	{
		this(sourcePlane, direction, yaw, pitch, halfWidth, height, DoorTravelerClass.LIVING, null);
	}

	public DoorTransit(
		DoorwayPlane sourcePlane,
		DoorwayCrossing.Direction direction,
		float yaw,
		float pitch,
		double halfWidth,
		double height,
		DoorTravelerClass travelerClass,
		DoorVec3 velocity)
	{
		this(
			sourcePlane,
			centeredCrossing(sourcePlane, direction),
			yaw,
			pitch,
			halfWidth,
			height,
			travelerClass,
			velocity);
	}

	public DoorTransit
	{
		Objects.requireNonNull(sourcePlane, "sourcePlane");
		Objects.requireNonNull(crossing, "crossing");
		Objects.requireNonNull(travelerClass, "travelerClass");
		if(!Float.isFinite(yaw) || !Float.isFinite(pitch))
		{
			throw new IllegalArgumentException("Transit orientation must be finite");
		}
		if(!Double.isFinite(halfWidth) || halfWidth <= 0.0D
			|| !Double.isFinite(height) || height <= 0.0D)
		{
			throw new IllegalArgumentException("Traveler dimensions must be finite and positive");
		}
		if(velocity != null && travelerClass != DoorTravelerClass.OBJECT)
		{
			throw new IllegalArgumentException("Only object travelers carry momentum through a door");
		}
	}

	public DoorwayCrossing.Direction direction()
	{
		return crossing.direction();
	}

	public boolean carriesMomentum()
	{
		return travelerClass == DoorTravelerClass.OBJECT;
	}

	/**
	 * Whether this transit consumes the source door's single armed open cycle.
	 *
	 * <p>Objects never do, so a whole volley passes through one swing. Neither
	 * does a closed-state contact surface, which has no open cycle to consume.</p>
	 */
	public boolean claimsOpenCycle()
	{
		return travelerClass != DoorTravelerClass.OBJECT && sourcePlane.openState() == DoorOpenState.OPEN;
	}

	private static DoorwayCrossing centeredCrossing(
		DoorwayPlane sourcePlane,
		DoorwayCrossing.Direction direction)
	{
		DoorwayPlane requiredPlane = Objects.requireNonNull(sourcePlane, "sourcePlane");
		DoorwayCrossing.Direction requiredDirection = Objects.requireNonNull(direction, "direction");
		double secondaryOffset = requiredPlane.horizontal() ? 0.0D : 1.0D;
		return new DoorwayCrossing(
			requiredPlane.center(),
			1.0D,
			0.0D,
			secondaryOffset,
			requiredDirection);
	}
}
