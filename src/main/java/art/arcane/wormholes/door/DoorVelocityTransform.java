package art.arcane.wormholes.door;

import java.util.Objects;

/**
 * Carries a traveler's momentum from one doorway plane to the other.
 *
 * <p>Minecraft yaw runs 0 = +Z, 90 = -X, so the heading implied by a yaw is
 * {@code (-sin(yaw), cos(yaw))}. Rotating a horizontal vector by a yaw delta
 * therefore uses that same handedness. Two hinged doors only ever differ by a
 * yaw, so that rotation alone carries them and the vertical component is
 * untouched: gravity is the same on both sides of the door.</p>
 *
 * <p>Once a trapdoor is involved the two planes are no longer parallel, so the
 * momentum is decomposed in the source plane's own frame - normal, lateral, and
 * the third in-plane axis - and rebuilt in the destination's. Speed is
 * preserved exactly because both frames are orthonormal, and the component along
 * the source normal lands on the destination normal, so a shot fired through a
 * doorway keeps going through the far one.</p>
 */
final class DoorVelocityTransform
{
	private DoorVelocityTransform()
	{
	}

	static DoorVec3 rotateYaw(DoorVec3 velocity, float yawDelta)
	{
		if(velocity == null)
		{
			return null;
		}
		if(!Float.isFinite(yawDelta))
		{
			throw new IllegalArgumentException("Yaw delta must be finite");
		}
		double radians = Math.toRadians(yawDelta);
		double cos = Math.cos(radians);
		double sin = Math.sin(radians);
		return new DoorVec3(
			(velocity.x() * cos) - (velocity.z() * sin),
			velocity.y(),
			(velocity.z() * cos) + (velocity.x() * sin));
	}

	/**
	 * Maps momentum between two planes of any orientation.
	 *
	 * <p>Two hinged doors keep the established front-to-front rule, where the
	 * traveler leaves the far door the way it came into the near one, so both
	 * in-plane-normal components flip. Every pairing that involves a trapdoor is a
	 * straight-through route instead: down stays down, up stays up.</p>
	 */
	static DoorVec3 map(DoorwayPlane source, DoorwayPlane destination, DoorVec3 velocity)
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		if(velocity == null)
		{
			return null;
		}
		int sign = DoorPlanePairing.mirrored(source, destination) ? -1 : 1;
		double normal = dot(velocity, source.normalX(), source.normalY(), source.normalZ()) * sign;
		double lateral = dot(velocity, lateralX(source), 0.0D, lateralZ(source)) * sign;
		double third = dot(velocity, thirdX(source), thirdY(source), thirdZ(source));
		return new DoorVec3(
			(normal * destination.normalX()) + (lateral * lateralX(destination)) + (third * thirdX(destination)),
			(normal * destination.normalY()) + (third * thirdY(destination)),
			(normal * destination.normalZ()) + (lateral * lateralZ(destination)) + (third * thirdZ(destination)));
	}

	/** The horizontal axis perpendicular to the facing, shared by both forms. */
	private static double lateralX(DoorwayPlane plane)
	{
		return -plane.facing().getModZ();
	}

	private static double lateralZ(DoorwayPlane plane)
	{
		return plane.facing().getModX();
	}

	/** {@code lateral x normal}: straight up for a door, away from the facing for a trapdoor. */
	private static double thirdX(DoorwayPlane plane)
	{
		return plane.horizontal() ? -plane.facing().getModX() : 0.0D;
	}

	private static double thirdY(DoorwayPlane plane)
	{
		return plane.horizontal() ? 0.0D : 1.0D;
	}

	private static double thirdZ(DoorwayPlane plane)
	{
		return plane.horizontal() ? -plane.facing().getModZ() : 0.0D;
	}

	private static double dot(DoorVec3 velocity, double x, double y, double z)
	{
		return (velocity.x() * x) + (velocity.y() * y) + (velocity.z() * z);
	}
}
