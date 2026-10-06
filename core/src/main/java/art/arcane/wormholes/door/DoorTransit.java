package art.arcane.wormholes.door;

import java.util.Objects;
import art.arcane.optics.crossing.PlaneCrossing;
import art.arcane.optics.math.Vec3d;

public record DoorTransit(
	DoorwayPlane sourcePlane,
	PlaneCrossing crossing,
	float yaw,
	float pitch,
	double halfWidth,
	double height,
	DoorTravelerClass travelerClass,
	Vec3d velocity,
	PlaneCrossing preparedCrossing)
{
    public DoorTransit(DoorwayPlane sourcePlane, PlaneCrossing crossing, float yaw, float pitch,
                       double halfWidth, double height, DoorTravelerClass travelerClass, Vec3d velocity) {
        this(sourcePlane, crossing, yaw, pitch, halfWidth, height, travelerClass, velocity, null);
    }

	public DoorTransit(DoorwayPlane sourcePlane, boolean frontSide, float yaw, float pitch)
	{
		this(sourcePlane, frontSide, yaw, pitch, 0.3D, 1.8D);
	}

	public DoorTransit(
		DoorwayPlane sourcePlane,
		boolean frontSide,
		float yaw,
		float pitch,
		double halfWidth,
		double height)
	{
		this(sourcePlane, frontSide, yaw, pitch, halfWidth, height, DoorTravelerClass.LIVING, null);
	}

	public DoorTransit(
		DoorwayPlane sourcePlane,
		boolean frontSide,
		float yaw,
		float pitch,
		double halfWidth,
		double height,
		DoorTravelerClass travelerClass,
		Vec3d velocity)
	{
		this(
			sourcePlane,
			centeredCrossing(sourcePlane, frontSide),
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
	}

	public int entrySideSign()
	{
		return crossing.frontSide() ? 1 : -1;
	}

	public int exitSideSign()
	{
		return -entrySideSign();
	}

	public boolean carriesMomentum()
	{
		return travelerClass == DoorTravelerClass.OBJECT || velocity != null;
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

	private static PlaneCrossing centeredCrossing(DoorwayPlane sourcePlane, boolean frontSide)
	{
		Vec3d center = Objects.requireNonNull(sourcePlane, "sourcePlane").center();
		return sourcePlane.crossingAt(center, new Vec3d(0.0D, 0.0D, 0.0D), frontSide);
	}
}
