package art.arcane.wormholes.door;

import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.wormholes.geometry.GeometryVector;

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

    public static DoorVec3 destinationPoint(DoorwayPlane destination, DoorTransit transit, int sideSign) {
        if (transit.preparedCrossing() == null) {
            return arrivalPoint(destination, transit, sideSign);
        }
        DoorVec3 center = destination.center();
        GeometryVector point = transit.preparedCrossing().outPoint(DoorApertureFrames.destinationFrame(transit.sourcePlane(), destination),
            new GeometryVector(center.x(), center.y(), center.z()));
        return new DoorVec3(point.x(), point.y(), point.z());
    }

    public static Facing destinationFacing(DoorwayPlane destination, DoorTransit transit, int sideSign) {
        if (transit.preparedCrossing() == null) {
            return arrivalFacing(destination, transit, sideSign);
        }
        GeometryVector look = transit.preparedCrossing().outLook(DoorApertureFrames.destinationFrame(transit.sourcePlane(), destination));
        double horizontal = Math.hypot(look.x(), look.z());
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x(), look.z()));
        if (yaw >= 180.0F) {
            yaw -= 360.0F;
        }
        return new Facing(yaw, (float) Math.toDegrees(Math.atan2(-look.y(), horizontal)));
    }

    public static DoorVec3 destinationVelocity(DoorwayPlane destination, DoorTransit transit, int sideSign) {
        if (transit.preparedCrossing() == null) {
            return DoorVelocityTransform.mapToSide(destination, transit, transit.velocity(), sideSign);
        }
        GeometryVector velocity = transit.preparedCrossing().outVelocity(DoorApertureFrames.destinationFrame(transit.sourcePlane(), destination));
        return new DoorVec3(velocity.x(), velocity.y(), velocity.z());
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

    public static Facing arrivalFacing(DoorwayPlane destination, DoorTransit transit, int sideSign) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(transit, "transit");
        if (sideSign != -1 && sideSign != 1) {
            throw new IllegalArgumentException("Arrival side must be -1 or 1");
        }
        DoorwayPlane source = transit.sourcePlane();
        double yaw = Math.toRadians(transit.yaw());
        double pitch = Math.toRadians(transit.pitch());
        double horizontal = Math.cos(pitch);
        DoorVec3 look = DoorVelocityTransform.mapToSide(destination, transit,
            new DoorVec3(-Math.sin(yaw) * horizontal, -Math.sin(pitch), Math.cos(yaw) * horizontal), sideSign);
        double projectedHorizontal = Math.hypot(look.x(), look.z());
        float targetYaw = projectedHorizontal < 1.0E-10D
            ? source.rotateYawTo(destination, transit.yaw())
            : (float) Math.toDegrees(Math.atan2(-look.x(), look.z()));
        if (targetYaw >= 180.0F) {
            targetYaw -= 360.0F;
        }
        return new Facing(targetYaw, (float) Math.toDegrees(Math.atan2(-look.y(), projectedHorizontal)));
    }

    public record Facing(float yaw, float pitch) {
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
