package art.arcane.wormholes.door;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

public final class DoorArrivals
{
    private static final int[] NEAR_Y_OFFSETS = {0, 1, -1, 2, -2};

	private static final double ARRIVAL_OFFSET = 1.0D;

	private DoorArrivals()
	{
	}

	public static DoorVec3 arrivalPoint(DoorwayPlane plane, DoorTransit transit)
	{
		Objects.requireNonNull(plane, "plane");
		Objects.requireNonNull(transit, "transit");
		return arrivalPoint(
			plane,
			transit,
			DoorPlanePairing.arrivalSideSign(transit.sourcePlane(), plane, transit.direction()));
	}

	/**
	 * The nominal landing point one side off a plane. A vertical plane pushes the
	 * traveler a stride clear of the doorway; a horizontal one places its feet on
	 * the plate for an upward exit and a full body below it for a downward one, so
	 * a fall keeps falling.
	 */
	public static DoorVec3 arrivalPoint(DoorwayPlane plane, DoorTransit transit, int sideSign)
	{
		Objects.requireNonNull(plane, "plane");
		Objects.requireNonNull(transit, "transit");
		if(transit.travelerClass() == DoorTravelerClass.OBJECT)
		{
			DoorVec3 aperturePoint = plane.equals(transit.sourcePlane())
				? transit.crossing().point()
				: DoorPlanePairing.mapAperturePoint(transit.sourcePlane(), plane, transit.crossing());
			if(plane.horizontal())
			{
				double y = horizontalArrivalY(plane, transit, sideSign);
				return new DoorVec3(aperturePoint.x(), y, aperturePoint.z());
			}
			double offset = arrivalOffset(transit) * sideSign;
			return new DoorVec3(
				aperturePoint.x() + (plane.normalX() * offset),
				aperturePoint.y(),
				aperturePoint.z() + (plane.normalZ() * offset));
		}
		if(plane.horizontal())
		{
			double y = horizontalArrivalY(plane, transit, sideSign);
			return new DoorVec3(plane.blockX() + 0.5D, y, plane.blockZ() + 0.5D);
		}
		return plane.sidePoint(sideSign, arrivalOffset(transit));
	}

	private static double horizontalArrivalY(DoorwayPlane plane, DoorTransit transit, int sideSign)
	{
		if(plane.contactSurface())
		{
			double surfaceY = plane.exposedSurfaceY(sideSign);
			return sideSign > 0 ? surfaceY : surfaceY - transit.height();
		}
		return sideSign > 0 ? plane.planeY() : plane.planeY() - transit.height();
	}

	public static float arrivalYaw(DoorwayPlane source, DoorwayPlane destination, DoorTransit transit)
	{
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(destination, "destination");
		Objects.requireNonNull(transit, "transit");
		return DoorPlanePairing.arrivalYaw(source, destination, transit.yaw());
	}

	public static Optional<DoorVec3> findSafeVerticalDoorStanding(
		DoorVec3 nominal,
		Predicate<DoorVec3> isSafe)
	{
		return findSafeVerticalDoorStanding(nominal, DoorPlanePairing.DOOR_ARRIVAL_Y_OFFSETS, isSafe);
	}

	public static Optional<DoorVec3> findSafeVerticalDoorStanding(
		DoorVec3 nominal,
		int[] verticalOffsets,
		Predicate<DoorVec3> isSafe)
	{
		Objects.requireNonNull(nominal, "nominal");
		Objects.requireNonNull(verticalOffsets, "verticalOffsets");
		Objects.requireNonNull(isSafe, "isSafe");
		for(int yOffset : verticalOffsets)
		{
			DoorVec3 candidate = new DoorVec3(nominal.x(), nominal.y() + yOffset, nominal.z());
			if(isSafe.test(candidate))
			{
				return Optional.of(candidate);
			}
		}
		return Optional.empty();
	}

	private static double arrivalOffset(DoorTransit transit)
	{
		return Math.max(ARRIVAL_OFFSET, 0.5D + transit.halfWidth() + DoorwayPlane.PORTAL_RECESS);
	}

    public static Optional<DoorVec3> findSafeNear(DoorVec3 stored, int radius, Predicate<DoorVec3> safe) {
        if (safe.test(stored)) {
            return Optional.of(stored);
        }
        int originX = (int) Math.floor(stored.x());
        int originY = (int) Math.floor(stored.y());
        int originZ = (int) Math.floor(stored.z());
        for (int distance = 1; distance <= radius; distance++) {
            for (int x = -distance; x <= distance; x++) {
                for (int z = -distance; z <= distance; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != distance) {
                        continue;
                    }
                    for (int yOffset : NEAR_Y_OFFSETS) {
                        DoorVec3 candidate = new DoorVec3(originX + x + 0.5D, originY + yOffset, originZ + z + 0.5D);
                        if (safe.test(candidate)) {
                            return Optional.of(candidate);
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }
}
